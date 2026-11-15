package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-002 part B (api spec 008), AC-API-08.1 and -08.2, RN-OPS-API-70 to -73: the capacity a new set of vehicles would give, ports as fakes. */
class PreviewVehicleChangeTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.plus(Duration.ofDays(10));
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID OTHER_TENANT = new UUID(0, 0xab);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);

  private FakeWorld world;
  private PreviewVehicleChange preview;
  private Departure departure;

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    preview = new PreviewVehicleChange(world, world, world);
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

  private PreviewVehicleChange.Preview ask(String code, UUID... vehicleIds) {
    return preview.execute(new PreviewVehicleChange.Command(TENANT, code, List.of(vehicleIds)));
  }

  @Test
  void A_preview_answers_the_departure_and_both_capacities() {
    Vehicle van = vehicle(1, 12, TENANT);
    Vehicle bus = vehicle(2, 30, TENANT);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id())));
    PreviewVehicleChange.Preview answer = ask("SAL-001", van.id(), bus.id());
    assertThat(answer.departureId()).isEqualTo(departure.id());
    assertThat(answer.currentEffectiveCapacity()).isEqualTo(12);
    // the lower of the capacity of the tour (18) and the total of the new set (42)
    assertThat(answer.newEffectiveCapacity()).isEqualTo(18);
  }

  @Test
  void A_preview_writes_nothing() {
    Vehicle van = vehicle(1, 12, TENANT);
    Vehicle bus = vehicle(2, 30, TENANT);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id())));
    ask("SAL-001", bus.id());
    assertThat(world.assignments.get(departure.id())).containsExactly(van.id());
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_vehicle_that_stays_is_not_an_overlap_with_its_own_departure() {
    Vehicle van = vehicle(1, 12, TENANT);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id())));
    assertThat(ask("SAL-001", van.id()).newEffectiveCapacity()).isEqualTo(12);
  }

  @Test
  void A_a_departure_that_is_not_scheduled_is_refused() {
    Vehicle van = vehicle(1, 12, TENANT);
    departure(2, START, END, DepartureStatus.IN_EXECUTION);
    assertThatThrownBy(() -> ask("SAL-002", van.id())).isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void A_a_tour_that_does_not_require_transport_is_refused() {
    Vehicle van = vehicle(1, 12, TENANT);
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, false));
    assertThatThrownBy(() -> ask("SAL-001", van.id())).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void A_an_unknown_vehicle_or_one_of_another_tenant_is_refused() {
    Vehicle foreign = vehicle(1, 12, OTHER_TENANT);
    assertThatThrownBy(() -> ask("SAL-001", new UUID(0, 9999))).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> ask("SAL-001", foreign.id())).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void A_a_vehicle_on_another_overlapping_departure_is_refused_naming_it() {
    Vehicle van = vehicle(1, 12, TENANT);
    Departure other = departure(2, START.plus(Duration.ofHours(4)), END.plus(Duration.ofHours(4)), DepartureStatus.SCHEDULED);
    world.assignments.put(other.id(), new LinkedHashSet<>(Set.of(van.id())));
    assertThatThrownBy(() -> ask("SAL-001", van.id())).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("SAL-002");
  }

  @Test
  void A_a_departure_of_another_tenant_is_not_found() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertThatThrownBy(() -> preview.execute(new PreviewVehicleChange.Command(OTHER_TENANT, "SAL-001", List.of(van.id()))))
        .isInstanceOf(DepartureNotFoundException.class);
  }

  @Test
  void A_without_the_catalog_the_answer_is_not_available() {
    Vehicle van = vehicle(1, 12, TENANT);
    world.catalogDown = true;
    assertThatThrownBy(() -> ask("SAL-001", van.id())).isInstanceOf(DependencyUnavailableException.class);
  }
}
