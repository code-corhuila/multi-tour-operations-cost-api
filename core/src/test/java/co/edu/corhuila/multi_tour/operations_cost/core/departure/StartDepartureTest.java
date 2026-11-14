package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Permission;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-003 (api spec 004), AC-API-04.4 to -04.6, -04.10, -04.12 and -04.13: recording the real start, ports as fakes. */
class StartDepartureTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.minus(Duration.ofHours(1));
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID OTHER_TENANT = new UUID(0, 0xab);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final String KEY = "start-key-0001";
  private static final String TRACE = "trace-42";

  private FakeWorld world;
  private StartDeparture start;
  private Departure departure;
  private final Caller person =
      new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.OPERATIONAL_COLLABORATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    start = new StartDeparture(world, world, world, new DepartureViews(world, world, world), world, world, Clock.fixed(NOW, ZoneOffset.UTC));
    departure = put(1, DepartureStatus.SCHEDULED, TENANT);
  }

  private Departure put(int n, DepartureStatus status, UUID tenant) {
    Departure created = new Departure(new UUID(0, 1000 + n), tenant, String.format("SAL-%03d", n), TOUR, START, END, "America/Bogota", status,
        ACCOUNT, START.minus(Duration.ofDays(2)));
    world.departures.put(created.id(), created);
    return created;
  }

  private StartDeparture.Command command(String code, String key, Instant at) {
    return new StartDeparture.Command(person, code, key, at, TRACE);
  }

  @Test
  void A_the_start_is_recorded_with_who_recorded_it_and_the_event_is_written_with_it() {
    StartDeparture.Started result = start.execute(command("SAL-001", KEY, NOW.minus(Duration.ofMinutes(30))));
    assertThat(result.recorded()).isTrue();
    Departure stored = world.departures.get(departure.id());
    assertThat(stored.status()).isEqualTo(DepartureStatus.IN_EXECUTION);
    assertThat(stored.execution().actualStart()).isEqualTo(NOW.minus(Duration.ofMinutes(30)));
    assertThat(stored.execution().startedBy()).isEqualTo(ACCOUNT);
    assertThat(result.view().departure()).isEqualTo(stored);
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaExecutionStarted");
      assertThat(event.aggregateId()).isEqualTo(departure.id());
      assertThat(event.tenantId()).isEqualTo(TENANT);
      assertThat(event.actorId()).isEqualTo(ACCOUNT);
      assertThat(event.correlationId()).isEqualTo(TRACE);
      assertThat(event.payload()).containsEntry("actualStart", NOW.minus(Duration.ofMinutes(30)).toString());
    });
    assertThat(world.keys).isNotEmpty();
  }

  @Test
  void A_the_answer_carries_the_capacity_and_the_seats_held() {
    Vehicle van = new Vehicle(new UUID(0, 2001), TENANT, "Van", 12, ACCOUNT, NOW);
    world.vehicleRows.put(van.id(), van);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id())));
    world.seats.put(departure.id(), new Reservations.Seats(12, 5));
    DepartureView view = start.execute(command("SAL-001", KEY, NOW)).view();
    assertThat(view.tourCapacity()).isEqualTo(18);
    assertThat(view.vehicleCapacity()).isEqualTo(12);
    assertThat(view.capacity()).isEqualTo(12);
    assertThat(view.transportRequired()).isTrue();
    assertThat(view.booked()).isEqualTo(5);
    assertThat(view.vehicles()).containsExactly(van);
  }

  @Test
  void A_the_same_key_answers_the_same_departure_and_writes_nothing_more() {
    start.execute(command("SAL-001", KEY, NOW.minus(Duration.ofMinutes(30))));
    StartDeparture.Started again = start.execute(command("SAL-001", KEY, NOW.minus(Duration.ofMinutes(10))));
    assertThat(again.recorded()).isFalse();
    assertThat(again.view().departure().execution().actualStart()).isEqualTo(NOW.minus(Duration.ofMinutes(30)));
    assertThat(world.events).hasSize(1);
  }

  @Test
  void A_the_key_of_a_start_is_not_the_key_of_a_scheduling() {
    world.register(TENANT, KEY, departure.id());
    assertThat(start.execute(command("SAL-001", KEY, NOW)).recorded()).isTrue();
  }

  @Test
  void A_a_start_before_the_schedule_or_in_the_future_is_refused_and_nothing_is_written() {
    assertThatThrownBy(() -> start.execute(command("SAL-001", KEY, START.minusSeconds(1)))).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> start.execute(command("SAL-001", KEY, NOW.plusSeconds(1)))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.departures.get(departure.id())).isEqualTo(departure);
    assertThat(world.events).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_a_departure_that_is_not_scheduled_is_refused_as_a_transition() {
    for (DepartureStatus other : new DepartureStatus[] {DepartureStatus.IN_EXECUTION, DepartureStatus.FINISHED, DepartureStatus.CANCELLED}) {
      put(2, other, TENANT);
      assertThatThrownBy(() -> start.execute(command("SAL-002", KEY, NOW))).as(other.name()).isInstanceOf(InvalidStatusTransitionException.class);
    }
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_the_state_is_judged_again_after_the_lock() {
    world.onLock = () -> put(1, DepartureStatus.IN_EXECUTION, TENANT);
    assertThatThrownBy(() -> start.execute(command("SAL-001", KEY, NOW))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.events).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_an_unknown_departure_or_one_of_another_tenant_is_not_found() {
    assertThatThrownBy(() -> start.execute(command("SAL-099", KEY, NOW))).isInstanceOf(DepartureNotFoundException.class);
    put(3, DepartureStatus.SCHEDULED, OTHER_TENANT);
    assertThatThrownBy(() -> start.execute(command("SAL-003", KEY, NOW))).isInstanceOf(DepartureNotFoundException.class);
  }

  @Test
  void A_start_with_reservations_down_writes_nothing() {
    world.reservationsDown = true;
    assertThatThrownBy(() -> start.execute(command("SAL-001", KEY, NOW))).isInstanceOf(DependencyUnavailableException.class);
    assertThat(world.departures.get(departure.id())).isEqualTo(departure);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_start_with_the_catalog_down_writes_nothing() {
    world.catalogDown = true;
    assertThatThrownBy(() -> start.execute(command("SAL-001", KEY, NOW))).isInstanceOf(DependencyUnavailableException.class);
    assertThat(world.departures.get(departure.id())).isEqualTo(departure);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_failed_event_leaves_the_departure_and_the_key_as_they_were() {
    world.outboxFails = true;
    assertThatThrownBy(() -> start.execute(command("SAL-001", KEY, NOW))).isInstanceOf(IllegalStateException.class);
    assertThat(world.departures.get(departure.id())).isEqualTo(departure);
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_the_request_is_judged_in_a_fixed_order_a_caller_that_is_not_one_before_the_key_before_the_trace_before_the_time() {
    Caller stranger = new Caller("not-a-uuid", TENANT.toString(), Set.of(Role.OPERATIONAL_COLLABORATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));
    assertThatThrownBy(() -> start.execute(new StartDeparture.Command(stranger, "SAL-001", "short", null, " ")))
        .isInstanceOf(co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException.class);
    assertThatThrownBy(() -> start.execute(command("SAL-001", "short", null)))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("Idempotency-Key"));
    assertThatThrownBy(() -> start.execute(new StartDeparture.Command(person, "SAL-001", KEY, null, " ")))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("X-Correlation-Id"));
  }

  @Test
  void A_a_bad_key_a_missing_time_or_a_bad_trace_is_refused_by_field() {
    assertThatThrownBy(() -> start.execute(command("SAL-001", "short", NOW)))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("Idempotency-Key"));
    assertThatThrownBy(() -> start.execute(command("SAL-001", KEY, null)))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("startedAt"));
    assertThatThrownBy(() -> start.execute(new StartDeparture.Command(person, "SAL-001", KEY, NOW, " ")))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("X-Correlation-Id"));
  }

  @Test
  void A_two_starts_with_the_same_key_answer_the_winner_to_the_loser() {
    Departure winner = departure.start(NOW.minus(Duration.ofMinutes(30)), ACCOUNT, NOW);
    world.onLock = () -> {
      world.departures.put(departure.id(), winner);
      world.keys.put(TENANT + "/registerStart/" + KEY, departure.id());
    };
    StartDeparture.Started loser = start.execute(command("SAL-001", KEY, NOW.minus(Duration.ofMinutes(10))));
    assertThat(loser.recorded()).isFalse();
    assertThat(loser.view().departure()).isEqualTo(winner);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_key_registered_an_instant_ago_answers_the_departure_it_was_registered_for() {
    world.racedDeparture = departure.id();
    StartDeparture.Started loser = start.execute(command("SAL-001", KEY, NOW));
    assertThat(loser.recorded()).isFalse();
    assertThat(loser.view().departure().id()).isEqualTo(departure.id());
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_key_used_for_another_departure_is_refused_and_not_answered_with_that_one() {
    Departure other = put(2, DepartureStatus.SCHEDULED, TENANT);
    start.execute(command("SAL-002", KEY, NOW));
    assertThatThrownBy(() -> start.execute(command("SAL-001", KEY, NOW))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.departures.get(departure.id())).isEqualTo(departure);
    assertThat(world.departures.get(other.id()).status()).isEqualTo(DepartureStatus.IN_EXECUTION);
  }
}
