package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Permission;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-003 part B (api spec 005), AC-API-05.6 to -05.11, -05.14: regularizing a departure that never started, ports as fakes. */
class RegularizeDepartureTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.minus(Duration.ofHours(20));
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final String KEY = "regularize-key-0001";
  private static final String TRACE = "trace-77";

  private FakeWorld world;
  private RegularizeDeparture regularize;
  private Departure departure;
  private final Caller person =
      new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.OPERATIONAL_COLLABORATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    regularize = new RegularizeDeparture(world, world, world, new DepartureViews(world, world, world), world, world, Clock.fixed(NOW, ZoneOffset.UTC));
    departure = put(1, DepartureStatus.SCHEDULED, true);
  }

  private Departure put(int n, DepartureStatus status, boolean marked) {
    Departure created = new Departure(new UUID(0, 1000 + n), TENANT, String.format("SAL-%03d", n), TOUR, START, END, "America/Bogota", status,
        ACCOUNT, START.minus(Duration.ofDays(2)));
    if (marked) {
      created = created.markPending(END.plusSeconds(1));
    }
    world.departures.put(created.id(), created);
    return created;
  }

  private RegularizeDeparture.Command command(String code, String key, RegularizeDeparture.Result result, Instant start, Instant end,
      String early, String interruption) {
    return new RegularizeDeparture.Command(person, code, key, result, start, end, early, interruption, TRACE);
  }

  private RegularizeDeparture.Command executed() {
    return command("SAL-001", KEY, RegularizeDeparture.Result.EXECUTED, START, END, null, null);
  }

  @Test
  void A_an_executed_departure_is_finished_and_tells_the_start_and_then_the_end() {
    RegularizeDeparture.Regularized result = regularize.execute(executed());
    assertThat(result.recorded()).isTrue();
    Departure stored = world.departures.get(departure.id());
    assertThat(stored.status()).isEqualTo(DepartureStatus.FINISHED);
    assertThat(stored.execution().regularizedBy()).isEqualTo(ACCOUNT);
    assertThat(stored.execution().regularizedAt()).isEqualTo(NOW);
    assertThat(result.view().departure()).isEqualTo(stored);
    assertThat(world.events).hasSize(2);
    assertThat(world.events).extracting(OutboxEvent::eventType).containsExactly("SalidaExecutionStarted", "SalidaFinalized");
    assertThat(world.events.get(0).occurredAt()).isBefore(world.events.get(1).occurredAt());
    assertThat(world.events).allSatisfy(event -> {
      assertThat(event.aggregateId()).isEqualTo(departure.id());
      assertThat(event.tenantId()).isEqualTo(TENANT);
      assertThat(event.actorId()).isEqualTo(ACCOUNT);
      assertThat(event.correlationId()).isEqualTo(TRACE);
    });
    assertThat(world.events.get(0).payload()).containsEntry("actualStart", START.toString());
    assertThat(world.events.get(1).payload()).containsEntry("actualEnd", END.toString()).containsEntry("interrupted", false);
    assertThat(world.keys).isNotEmpty();
  }

  @Test
  void A_an_interrupted_departure_says_so_in_its_end_and_keeps_the_early_start_reason() {
    Instant early = START.minus(Duration.ofMinutes(10));
    regularize.execute(command("SAL-001", KEY, RegularizeDeparture.Result.INTERRUPTED, early, END, "Left before the schedule", "Heavy rain"));
    Departure stored = world.departures.get(departure.id());
    assertThat(stored.execution().interrupted()).isTrue();
    assertThat(stored.execution().interruptionCause()).isEqualTo("Heavy rain");
    assertThat(stored.execution().earlyStartReason()).isEqualTo("Left before the schedule");
    assertThat(world.events.get(1).payload()).containsEntry("interrupted", true);
    assertThat(world.events.get(1).payload()).doesNotContainValue("Heavy rain");
  }

  @Test
  void A_the_same_key_answers_the_same_departure_and_writes_nothing_more() {
    regularize.execute(executed());
    RegularizeDeparture.Regularized again = regularize.execute(executed());
    assertThat(again.recorded()).isFalse();
    assertThat(again.view().departure().status()).isEqualTo(DepartureStatus.FINISHED);
    assertThat(world.events).hasSize(2);
  }

  @Test
  void A_a_departure_that_is_not_scheduled_and_marked_is_refused_before_another_domain_is_asked() {
    put(2, DepartureStatus.SCHEDULED, false);
    put(3, DepartureStatus.FINISHED, false);
    put(4, DepartureStatus.CANCELLED, false);
    world.reservationsDown = true;
    for (String code : new String[] {"SAL-002", "SAL-003", "SAL-004"}) {
      assertThatThrownBy(() -> regularize.execute(command(code, KEY, RegularizeDeparture.Result.EXECUTED, START, END, null, null)))
          .isInstanceOf(InvalidStatusTransitionException.class);
    }
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_not_carried_out_is_refused_until_a_departure_can_be_cancelled() {
    assertThatThrownBy(() -> regularize.execute(command("SAL-001", KEY, RegularizeDeparture.Result.NOT_CARRIED_OUT, START, END, null, null)))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.departures.get(departure.id()).status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_with_reservations_down_nothing_is_written() {
    world.reservationsDown = true;
    assertThatThrownBy(() -> regularize.execute(executed())).isInstanceOf(DependencyUnavailableException.class);
    assertThat(world.departures.get(departure.id()).status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(world.events).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_the_request_is_judged_in_a_fixed_order_caller_key_trace_then_the_shape() {
    Caller stranger = new Caller("not-a-uuid", TENANT.toString(), Set.of(Role.OPERATIONAL_COLLABORATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));
    assertThatThrownBy(() -> regularize.execute(new RegularizeDeparture.Command(stranger, "SAL-001", "short", null, null, null, null, null, " ")))
        .isInstanceOf(CallerNotAllowedException.class);
    assertThatThrownBy(() -> regularize.execute(command("SAL-001", "short", null, null, null, null, null)))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("Idempotency-Key"));
    assertThatThrownBy(() -> regularize.execute(new RegularizeDeparture.Command(person, "SAL-001", KEY, null, null, null, null, null, " ")))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("X-Correlation-Id"));
    assertThatThrownBy(() -> regularize.execute(command("SAL-001", KEY, null, START, END, null, null)))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("result"));
    assertThatThrownBy(() -> regularize.execute(command("SAL-001", KEY, RegularizeDeparture.Result.INTERRUPTED, START, END, null, null)))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("interruption"));
    assertThatThrownBy(() -> regularize.execute(command("SAL-001", KEY, RegularizeDeparture.Result.EXECUTED, START, END, null, "Rain")))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("interruption"));
  }

  @Test
  void A_hours_in_the_future_or_out_of_order_are_refused_and_nothing_changes() {
    assertThatThrownBy(() -> regularize.execute(command("SAL-001", KEY, RegularizeDeparture.Result.EXECUTED, START, NOW.plusSeconds(1), null, null)))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.departures.get(departure.id()).status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(world.events).isEmpty();
    assertThat(world.keys).isEmpty();
  }
}
