package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Permission;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-004 (api spec 006), AC-API-06.1 to -06.6: what each reservation of a running departure received, ports as fakes. */
class SaveServicesTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.minus(Duration.ofHours(4));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final UUID R1 = new UUID(0, 0x101);
  private static final UUID R2 = new UUID(0, 0x102);
  private static final UUID R3 = new UUID(0, 0x103);
  private static final String KEY = "services-key-01";
  private static final String TRACE = "trace-42";

  private FakeWorld world;
  private SaveServices save;
  private Departure running;
  private final Caller person =
      new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, false));
    DepartureViews views = new DepartureViews(world, world, world);
    save = new SaveServices(world, world, world, world, new ServicesReader(world, world, world), views, world, Clock.fixed(NOW, ZoneOffset.UTC));
    running = departure(1, DepartureStatus.SCHEDULED).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(running.id(), running);
    world.reservationRows.put(running.id(), List.of(
        new ReservationOfDeparture(R1, "Ana Perez", 4, ReservationState.IN_EXECUTION),
        new ReservationOfDeparture(R2, "Luis Gomez", 2, ReservationState.IN_EXECUTION),
        new ReservationOfDeparture(R3, "Eva Rios", 3, ReservationState.CANCELLED)));
  }

  private Departure departure(int n, DepartureStatus status) {
    Departure created = new Departure(new UUID(0, 1000 + n), TENANT, String.format("SAL-%03d", n), TOUR, START, START.plus(Duration.ofHours(8)),
        "America/Bogota", status, ACCOUNT, START.minus(Duration.ofDays(2)));
    world.departures.put(created.id(), created);
    return created;
  }

  private static ServiceRecord delivered(UUID reservation, int served, String cause) {
    return new ServiceRecord(reservation, ServiceResult.DELIVERED, served, cause, false);
  }

  private SaveServices.Command command(String code, String key, ServiceRecord... entries) {
    return new SaveServices.Command(person, code, key, List.of(entries), TRACE);
  }

  @Test
  void A_the_services_are_recorded_and_the_real_base_is_answered() {
    SaveServices.Saved result = save.execute(command("SAL-001", KEY, delivered(R1, 4, null), delivered(R2, 1, "one stayed")));
    assertThat(result.recorded()).isTrue();
    assertThat(world.serviceRows.get(running.id())).containsOnlyKeys(R1, R2);
    assertThat(world.servicesRecordedBy.get(running.id())).isEqualTo(ACCOUNT);
    assertThat(result.services()).isPresent();
    assertThat(result.services().get().travelersServed()).isEqualTo(5);
    assertThat(result.services().get().reservations()).hasSize(3);
    assertThat(result.services().get().reservations().get(0).recorded()).contains(delivered(R1, 4, null));
    assertThat(result.services().get().reservations().get(2).recorded()).isEmpty();
  }

  @Test
  void A_reserved_travelers_are_never_written() {
    save.execute(command("SAL-001", KEY, delivered(R1, 4, null)));
    assertThat(world.reservationRows.get(running.id()).get(0).travelers()).isEqualTo(4);
  }

  @Test
  void A_reservation_of_another_departure_is_refused() {
    UUID stranger = new UUID(0, 0x999);
    assertThatThrownBy(() -> save.execute(command("SAL-001", KEY, delivered(stranger, 1, null))))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.serviceRows).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_reservation_not_in_execution_is_refused() {
    assertThatThrownBy(() -> save.execute(command("SAL-001", KEY, delivered(R3, 1, "x")))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.serviceRows).isEmpty();
  }

  @Test
  void A_services_of_a_cancelled_reservation_are_refused() {
    world.cancellationRows.put(running.id(), java.util.Map.of(R1, new ExtraordinaryCancellation(R1, "an emergency", ACCOUNT, NOW)));
    assertThatThrownBy(() -> save.execute(command("SAL-001", KEY, delivered(R2, 1, "x"), delivered(R1, 4, null))))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.serviceRows).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_more_served_than_reserved_is_refused_and_nothing_is_written() {
    assertThatThrownBy(() -> save.execute(command("SAL-001", KEY, delivered(R2, 1, "x"), delivered(R1, 5, null))))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.serviceRows).isEmpty();
  }

  @Test
  void A_a_missing_cause_writes_nothing() {
    assertThatThrownBy(() -> save.execute(command("SAL-001", KEY, delivered(R1, 4, null), delivered(R2, 1, null))))
        .isInstanceOf(InvalidDataException.class);
    assertThat(world.serviceRows).isEmpty();
  }

  @Test
  void A_a_departure_that_is_not_in_execution_is_refused_before_asking_anyone() {
    Departure finished = running.finish(NOW, ACCOUNT, NOW, null);
    world.departures.put(finished.id(), finished);
    assertThatThrownBy(() -> save.execute(command("SAL-001", KEY, delivered(R1, 4, null)))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.reservationCalls).isZero();
  }

  @Test
  void A_reservations_down_writes_nothing() {
    world.reservationsDown = true;
    assertThatThrownBy(() -> save.execute(command("SAL-001", KEY, delivered(R1, 4, null)))).isInstanceOf(DependencyUnavailableException.class);
    assertThat(world.serviceRows).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_the_same_reservation_twice_in_one_save_is_refused() {
    assertThatThrownBy(() -> save.execute(command("SAL-001", KEY, delivered(R1, 4, null), delivered(R1, 3, "x"))))
        .isInstanceOf(InvalidDataException.class);
  }

  @Test
  void A_a_save_with_no_entries_is_refused() {
    assertThatThrownBy(() -> save.execute(command("SAL-001", KEY))).isInstanceOf(InvalidDataException.class);
  }

  @Test
  void A_the_same_key_answers_the_same_departure_and_writes_once() {
    save.execute(command("SAL-001", KEY, delivered(R1, 4, null)));
    SaveServices.Saved again = save.execute(command("SAL-001", KEY, delivered(R1, 4, null)));
    assertThat(again.recorded()).isFalse();
    assertThat(world.keys).hasSize(1);
  }

  @Test
  void A_a_key_used_for_another_departure_is_refused() {
    Departure other = departure(2, DepartureStatus.SCHEDULED).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(other.id(), other);
    save.execute(command("SAL-001", KEY, delivered(R1, 4, null)));
    assertThatThrownBy(() -> save.execute(command("SAL-002", KEY, delivered(R1, 4, null)))).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void A_saving_again_with_a_new_key_replaces_the_record_of_the_reservation() {
    save.execute(command("SAL-001", KEY, delivered(R1, 4, null)));
    save.execute(command("SAL-001", "services-key-02", delivered(R1, 2, "two stayed")));
    assertThat(world.serviceRows.get(running.id()).get(R1).travelersServed()).isEqualTo(2);
  }

  @Test
  void A_a_finish_that_wins_the_lock_refuses_the_save_and_writes_nothing() {
    world.onLock = () -> world.departures.put(running.id(), running.finish(NOW, ACCOUNT, NOW, null));
    assertThatThrownBy(() -> save.execute(command("SAL-001", KEY, delivered(R1, 4, null)))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.serviceRows).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_the_departure_of_another_tenant_is_not_found() {
    Caller stranger = new Caller(ACCOUNT.toString(), new UUID(0, 0xab).toString(), Set.of(Role.TENANT_ADMINISTRATOR),
        Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));
    assertThatThrownBy(() -> save.execute(new SaveServices.Command(stranger, "SAL-001", KEY, List.of(delivered(R1, 4, null)), TRACE)))
        .isInstanceOf(DepartureNotFoundException.class);
  }
}
