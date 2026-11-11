package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-002 part B (api spec 008), AC-API-08.2 to -08.4, RN-OPS-API-72 to -75: putting the whole new set of vehicles on a departure, ports as fakes. */
class ReplaceDepartureVehiclesTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.plus(Duration.ofDays(10));
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID OTHER_TENANT = new UUID(0, 0xab);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final String TRACE = "trace-42";
  private static final String KEY = "saga-step-0001";

  private FakeWorld world;
  private ReplaceDepartureVehicles replace;
  private Departure departure;

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    replace = new ReplaceDepartureVehicles(world, world, world, world, world, world, Clock.fixed(NOW, ZoneOffset.UTC));
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

  private ReplaceDepartureVehicles.Replaced run(String code, String key, UUID... vehicleIds) {
    return replace.execute(new ReplaceDepartureVehicles.Command(TENANT, code, List.of(vehicleIds), key, TRACE));
  }

  @Test
  void A_the_departure_ends_with_exactly_the_set_and_the_total_goes_in_the_event() {
    Vehicle van = vehicle(1, 12, TENANT);
    Vehicle bus = vehicle(2, 30, TENANT);
    Vehicle minibus = vehicle(3, 20, TENANT);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id(), bus.id())));
    ReplaceDepartureVehicles.Replaced result = run("SAL-001", KEY, bus.id(), minibus.id());
    assertThat(result.changed()).isTrue();
    assertThat(result.vehicles()).containsExactlyInAnyOrder(bus, minibus);
    assertThat(world.assignments.get(departure.id())).containsExactlyInAnyOrder(bus.id(), minibus.id());
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaVehiclesChanged");
      assertThat(event.aggregateId()).isEqualTo(departure.id());
      assertThat(event.tenantId()).isEqualTo(TENANT);
      assertThat(event.actorId()).isNull();
      assertThat(event.correlationId()).isEqualTo(TRACE);
      assertThat(event.payload()).containsEntry("salidaId", departure.id().toString()).containsEntry("vehicleTotalCapacity", 50);
    });
  }

  @Test
  void A_a_set_equal_to_the_current_one_changes_nothing_and_writes_no_event() {
    Vehicle van = vehicle(1, 12, TENANT);
    run("SAL-001", KEY, van.id());
    ReplaceDepartureVehicles.Replaced again = run("SAL-001", KEY, van.id());
    assertThat(again.changed()).isFalse();
    assertThat(again.vehicles()).containsExactly(van);
    assertThat(world.events).hasSize(1);
  }

  @Test
  void A_a_vehicle_that_stays_is_not_an_overlap_with_its_own_departure() {
    Vehicle van = vehicle(1, 12, TENANT);
    Vehicle bus = vehicle(2, 30, TENANT);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id())));
    assertThat(run("SAL-001", KEY, van.id(), bus.id()).changed()).isTrue();
  }

  @Test
  void A_a_departure_that_is_not_scheduled_is_refused_and_nothing_changes() {
    Vehicle van = vehicle(1, 12, TENANT);
    departure(2, START, END, DepartureStatus.IN_EXECUTION);
    assertThatThrownBy(() -> run("SAL-002", KEY, van.id())).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_tour_without_transport_an_unknown_vehicle_or_an_overlap_is_refused_and_nothing_changes() {
    Vehicle van = vehicle(1, 12, TENANT);
    Vehicle bus = vehicle(2, 30, TENANT);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id())));
    Departure other = departure(2, START.plus(Duration.ofHours(4)), END.plus(Duration.ofHours(4)), DepartureStatus.SCHEDULED);
    world.assignments.put(other.id(), new LinkedHashSet<>(Set.of(bus.id())));
    assertThatThrownBy(() -> run("SAL-001", KEY, new UUID(0, 9999))).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> run("SAL-001", KEY, bus.id())).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("SAL-002");
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, false));
    assertThatThrownBy(() -> run("SAL-001", KEY, van.id())).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.assignments.get(departure.id())).containsExactly(van.id());
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_departure_of_another_tenant_is_not_found() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertThatThrownBy(() -> replace.execute(new ReplaceDepartureVehicles.Command(OTHER_TENANT, "SAL-001", List.of(van.id()), KEY, TRACE)))
        .isInstanceOf(DepartureNotFoundException.class);
  }

  @Test
  void A_without_the_catalog_nothing_changes() {
    Vehicle van = vehicle(1, 12, TENANT);
    world.catalogDown = true;
    assertThatThrownBy(() -> run("SAL-001", KEY, van.id())).isInstanceOf(DependencyUnavailableException.class);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_missing_or_malformed_key_or_trace_is_refused() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertThatThrownBy(() -> run("SAL-001", null, van.id())).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> run("SAL-001", "short", van.id())).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> replace.execute(new ReplaceDepartureVehicles.Command(TENANT, "SAL-001", List.of(van.id()), KEY, " ")))
        .isInstanceOf(InvalidDataException.class);
  }
}
