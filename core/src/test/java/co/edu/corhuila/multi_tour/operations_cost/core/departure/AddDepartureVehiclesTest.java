package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-002 (api spec 003), AC-API-03.4, -03.5, -03.7 and -03.9 to -03.13: adding vehicles to a scheduled departure, ports as fakes. */
class AddDepartureVehiclesTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.plus(Duration.ofDays(10));
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID OTHER_TENANT = new UUID(0, 0xab);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final String TRACE = "trace-42";

  private FakeWorld world;
  private AddDepartureVehicles add;
  private Departure departure;
  private final Caller admin = new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    add = new AddDepartureVehicles(world, world, world, world, world, world, Clock.fixed(NOW, ZoneOffset.UTC));
    departure = departure(1, START, END, DepartureStatus.SCHEDULED);
  }

  private Departure departure(int n, Instant start, Instant end, DepartureStatus status) {
    Departure created = new Departure(new UUID(0, 1000 + n), TENANT, String.format("SAL-%03d", n), TOUR, start, end, "America/Bogota",
        status, ACCOUNT, NOW);
    world.departures.put(created.id(), created);
    return created;
  }

  private Vehicle vehicle(int n, int capacity, UUID tenant) {
    Vehicle v = new Vehicle(new UUID(0, 2000 + n), tenant, "Vehicle " + n, capacity, ACCOUNT, NOW);
    world.vehicleRows.put(v.id(), v);
    return v;
  }

  private AddDepartureVehicles.Command command(String code, UUID... vehicleIds) {
    return new AddDepartureVehicles.Command(admin, code, List.of(vehicleIds), TRACE);
  }

  @Test
  void A_vehicles_are_added_and_the_total_goes_in_the_event() {
    Vehicle van = vehicle(1, 12, TENANT);
    Vehicle bus = vehicle(2, 30, TENANT);
    AddDepartureVehicles.Added result = add.execute(command("SAL-001", van.id(), bus.id()));
    assertThat(result.changed()).isTrue();
    assertThat(result.vehicles()).containsExactlyInAnyOrder(van, bus);
    assertThat(result.tour().capacity()).isEqualTo(18);
    assertThat(world.assignments.get(departure.id())).containsExactlyInAnyOrder(van.id(), bus.id());
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaVehiclesChanged");
      assertThat(event.aggregateId()).isEqualTo(departure.id());
      assertThat(event.tenantId()).isEqualTo(TENANT);
      assertThat(event.actorId()).isEqualTo(ACCOUNT);
      assertThat(event.correlationId()).isEqualTo(TRACE);
      assertThat(event.occurredAt()).isEqualTo(NOW);
      assertThat(event.payload()).containsOnlyKeys("tenantId", "salidaId", "vehicleTotalCapacity")
          .containsEntry("salidaId", departure.id().toString()).containsEntry("vehicleTotalCapacity", 42);
    });
  }

  @Test
  void A_the_total_of_the_event_includes_the_vehicles_already_there() {
    Vehicle van = vehicle(1, 12, TENANT);
    Vehicle bus = vehicle(2, 30, TENANT);
    add.execute(command("SAL-001", van.id()));
    add.execute(command("SAL-001", bus.id()));
    assertThat(world.events).hasSize(2);
    assertThat(world.events.get(1).payload()).containsEntry("vehicleTotalCapacity", 42);
  }

  @Test
  void A_adding_a_vehicle_already_there_changes_nothing_and_writes_no_event() {
    Vehicle van = vehicle(1, 12, TENANT);
    add.execute(command("SAL-001", van.id()));
    AddDepartureVehicles.Added again = add.execute(command("SAL-001", van.id()));
    assertThat(again.changed()).isFalse();
    assertThat(again.vehicles()).containsExactly(van);
    assertThat(world.events).hasSize(1);
  }

  @Test
  void A_overlapping_assignment_is_refused_naming_the_other_departure_and_nothing_changes() {
    Vehicle van = vehicle(1, 12, TENANT);
    Departure other = departure(2, START.plus(Duration.ofHours(4)), END.plus(Duration.ofHours(4)), DepartureStatus.SCHEDULED);
    world.assignments.put(other.id(), new LinkedHashSet<>(Set.of(van.id())));
    assertThatThrownBy(() -> add.execute(command("SAL-001", van.id())))
        .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("SAL-002");
    assertThat(world.assignments.get(departure.id())).isNull();
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_touching_intervals_are_allowed() {
    Vehicle van = vehicle(1, 12, TENANT);
    Departure before = departure(2, START.minus(Duration.ofHours(8)), START, DepartureStatus.SCHEDULED);
    world.assignments.put(before.id(), new LinkedHashSet<>(Set.of(van.id())));
    assertThat(add.execute(command("SAL-001", van.id())).changed()).isTrue();
  }

  @Test
  void A_a_cancelled_or_finished_departure_frees_its_vehicles() {
    Vehicle van = vehicle(1, 12, TENANT);
    Departure cancelled = departure(2, START, END, DepartureStatus.CANCELLED);
    world.assignments.put(cancelled.id(), new LinkedHashSet<>(Set.of(van.id())));
    Departure finished = departure(3, START, END, DepartureStatus.FINISHED);
    world.assignments.put(finished.id(), new LinkedHashSet<>(Set.of(van.id())));
    assertThat(add.execute(command("SAL-001", van.id())).changed()).isTrue();
  }

  @Test
  void A_a_vehicle_that_does_not_exist_or_is_of_another_tenant_is_unknown() {
    Vehicle foreign = vehicle(1, 12, OTHER_TENANT);
    assertThatThrownBy(() -> add.execute(command("SAL-001", foreign.id()))).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> add.execute(command("SAL-001", new UUID(0, 9999)))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.assignments).isEmpty();
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_vehicles_for_a_tour_without_transport_are_refused() {
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, false));
    Vehicle van = vehicle(1, 12, TENANT);
    assertThatThrownBy(() -> add.execute(command("SAL-001", van.id()))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_departure_that_is_not_scheduled_cannot_receive_vehicles_and_nobody_is_asked() {
    Vehicle van = vehicle(1, 12, TENANT);
    departure(2, START, END, DepartureStatus.IN_EXECUTION);
    assertThatThrownBy(() -> add.execute(command("SAL-002", van.id()))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.catalogCalls).isZero();
  }

  @Test
  void A_another_tenants_or_unknown_departure_is_not_found() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertThatThrownBy(() -> add.execute(command("SAL-099", van.id()))).isInstanceOf(DepartureNotFoundException.class);
    Caller other = new Caller(ACCOUNT.toString(), OTHER_TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
    assertThatThrownBy(() -> add.execute(new AddDepartureVehicles.Command(other, "SAL-001", List.of(van.id()), TRACE)))
        .isInstanceOf(DepartureNotFoundException.class);
  }

  @Test
  void A_a_catalog_that_does_not_answer_writes_nothing() {
    Vehicle van = vehicle(1, 12, TENANT);
    world.catalogDown = true;
    assertThatThrownBy(() -> add.execute(command("SAL-001", van.id()))).isInstanceOf(DependencyUnavailableException.class);
    assertThat(world.assignments).isEmpty();
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_failed_event_leaves_no_assignment() {
    Vehicle van = vehicle(1, 12, TENANT);
    world.outboxFails = true;
    assertThatThrownBy(() -> add.execute(command("SAL-001", van.id()))).isInstanceOf(IllegalStateException.class);
    assertThat(world.assignments.get(departure.id())).isNull();
  }

  @Test
  void A_the_list_of_ids_must_be_between_one_and_twenty_and_without_repeats() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertThatThrownBy(() -> add.execute(new AddDepartureVehicles.Command(admin, "SAL-001", List.of(), TRACE)))
        .isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> add.execute(command("SAL-001", van.id(), van.id()))).isInstanceOf(InvalidDataException.class);
    List<UUID> twentyOne = IntStream.range(0, 21).mapToObj(n -> new UUID(0, 5000 + n)).toList();
    assertThatThrownBy(() -> add.execute(new AddDepartureVehicles.Command(admin, "SAL-001", twentyOne, TRACE)))
        .isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> add.execute(new AddDepartureVehicles.Command(admin, "SAL-001", null, TRACE)))
        .isInstanceOf(InvalidDataException.class);
  }

  @Test
  void A_a_departure_cancelled_while_the_request_ran_is_refused_inside_the_transaction() {
    Vehicle van = vehicle(1, 12, TENANT);
    world.onLock = () -> world.departures.put(departure.id(),
        new Departure(departure.id(), TENANT, "SAL-001", TOUR, START, END, "America/Bogota", DepartureStatus.CANCELLED, ACCOUNT, NOW));
    assertThatThrownBy(() -> add.execute(command("SAL-001", van.id()))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.assignments).isEmpty();
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_the_dates_that_changed_while_the_request_ran_are_the_ones_that_are_checked() {
    Vehicle van = vehicle(1, 12, TENANT);
    Instant movedStart = START.plus(Duration.ofDays(3));
    Departure other = departure(2, movedStart, movedStart.plus(Duration.ofHours(8)), DepartureStatus.SCHEDULED);
    world.assignments.put(other.id(), new LinkedHashSet<>(Set.of(van.id())));
    world.onLock = () -> world.departures.put(departure.id(),
        new Departure(departure.id(), TENANT, "SAL-001", TOUR, movedStart, movedStart.plus(Duration.ofHours(8)), "America/Bogota",
            DepartureStatus.SCHEDULED, ACCOUNT, NOW));
    assertThatThrownBy(() -> add.execute(command("SAL-001", van.id())))
        .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("SAL-002");
    assertThat(world.assignments.get(departure.id())).isNull();
  }
}
