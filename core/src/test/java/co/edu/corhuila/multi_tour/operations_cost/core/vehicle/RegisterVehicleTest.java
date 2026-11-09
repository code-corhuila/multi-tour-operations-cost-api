package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.BusinessRuleViolationException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-002 (api spec 003), AC-API-03.1 to -03.3 and -03.10: register and list the vehicles of the tenant, with the ports as fakes. */
class RegisterVehicleTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID OTHER_TENANT = new UUID(0, 0xab);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final String KEY = "key-0001-abcdef";
  private static final String TRACE = "trace-42";

  private VehicleWorld world;
  private RegisterVehicle register;
  private ListVehicles list;
  private final Caller admin = new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));

  @BeforeEach
  void setUp() {
    world = new VehicleWorld();
    register = new RegisterVehicle(world, world, world, world, Clock.fixed(NOW, ZoneOffset.UTC));
    list = new ListVehicles(world);
  }

  private RegisterVehicle.Command command(String key, String name, int capacity) {
    return new RegisterVehicle.Command(admin, key, name, capacity, TRACE);
  }

  @Test
  void A_a_vehicle_is_registered_for_the_tenant_of_the_caller() {
    RegisterVehicle.Registered result = register.execute(command(KEY, "Van VAN-204", 12));
    assertThat(result.created()).isTrue();
    Vehicle vehicle = result.vehicle();
    assertThat(vehicle.name()).isEqualTo("Van VAN-204");
    assertThat(vehicle.capacity()).isEqualTo(12);
    assertThat(vehicle.tenantId()).isEqualTo(TENANT);
    assertThat(vehicle.createdBy()).isEqualTo(ACCOUNT);
    assertThat(vehicle.createdAt()).isEqualTo(NOW);
    assertThat(world.rows).containsValue(vehicle);
  }

  @Test
  void A_a_name_already_used_in_the_tenant_is_refused_ignoring_case_and_spaces() {
    register.execute(command(KEY, "Van 1", 12));
    assertThatThrownBy(() -> register.execute(command("key-0002-abcdef", "  van 1 ", 10)))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.rows).hasSize(1);
  }

  @Test
  void A_the_same_name_in_another_tenant_is_allowed() {
    Caller other = new Caller(ACCOUNT.toString(), OTHER_TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
    register.execute(command(KEY, "Van 1", 12));
    register.execute(new RegisterVehicle.Command(other, KEY, "Van 1", 12, TRACE));
    assertThat(world.rows).hasSize(2);
  }

  @Test
  void A_the_same_key_answers_the_vehicle_already_created_and_creates_nothing() {
    Vehicle first = register.execute(command(KEY, "Van 1", 12)).vehicle();
    RegisterVehicle.Registered again = register.execute(command(KEY, "Another name", 30));
    assertThat(again.created()).isFalse();
    assertThat(again.vehicle()).isEqualTo(first);
    assertThat(world.rows).hasSize(1);
  }

  @Test
  void A_a_key_of_another_tenant_is_another_key() {
    Caller other = new Caller(ACCOUNT.toString(), OTHER_TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
    register.execute(command(KEY, "Van 1", 12));
    assertThat(register.execute(new RegisterVehicle.Command(other, KEY, "Bus", 30, TRACE)).created()).isTrue();
  }

  @Test
  void A_the_key_and_the_vehicle_are_written_together_or_not_at_all() {
    world.keysFail = true;
    assertThatThrownBy(() -> register.execute(command(KEY, "Van 1", 12))).isInstanceOf(IllegalStateException.class);
    assertThat(world.rows).isEmpty();
  }

  @Test
  void A_a_bad_shape_is_invalid_data_and_nothing_is_written() {
    assertThatThrownBy(() -> register.execute(command(KEY, "", 12))).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> register.execute(command(KEY, "Van", 0))).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> register.execute(command(KEY, "Van", 101))).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> register.execute(command("short", "Van", 12))).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> register.execute(command(null, "Van", 12))).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> register.execute(new RegisterVehicle.Command(admin, KEY, "Van", 12, " "))).isInstanceOf(InvalidDataException.class);
    assertThat(world.rows).isEmpty();
  }

  @Test
  void A_a_caller_that_is_not_a_person_of_a_tenant_is_not_allowed() {
    Caller notAUuid = new Caller("not-a-uuid", TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
    assertThatThrownBy(() -> register.execute(new RegisterVehicle.Command(notAUuid, KEY, "Van", 12, TRACE)))
        .isInstanceOf(CallerNotAllowedException.class);
  }

  @Test
  void A_the_list_is_sorted_by_name_paged_and_only_of_the_tenant() {
    register.execute(command("key-0001-abcdef", "bus", 30));
    register.execute(command("key-0002-abcdef", "Van A", 12));
    register.execute(command("key-0003-abcdef", "Boat", 10));
    register.execute(new RegisterVehicle.Command(
        new Caller(ACCOUNT.toString(), OTHER_TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR)), KEY, "Other", 5, TRACE));
    var page = list.execute(new ListVehicles.Query(admin, 1, 2));
    assertThat(page.data()).extracting(Vehicle::name).containsExactly("Boat", "bus");
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.totalPages()).isEqualTo(2);
    assertThat(list.execute(new ListVehicles.Query(admin, 2, 2)).data()).extracting(Vehicle::name).containsExactly("Van A");
  }

  @Test
  void A_the_limits_of_the_page_are_refused_never_trimmed() {
    assertThatThrownBy(() -> list.execute(new ListVehicles.Query(admin, 0, 20))).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> list.execute(new ListVehicles.Query(admin, 1, 0))).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> list.execute(new ListVehicles.Query(admin, 1, 101))).isInstanceOf(InvalidDataException.class);
    assertThat(list.execute(new ListVehicles.Query(admin, 1, 100)).data()).isEmpty();
  }
}
