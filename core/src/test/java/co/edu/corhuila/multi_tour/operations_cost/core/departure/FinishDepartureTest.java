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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-003 (api spec 004), AC-API-04.7 to -04.10, -04.12 and -04.13: recording the end or the interruption, ports as fakes. */
class FinishDepartureTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.minus(Duration.ofHours(4));
  private static final Instant ACTUAL_START = START.plus(Duration.ofMinutes(5));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID OTHER_TENANT = new UUID(0, 0xab);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID STARTER = new UUID(0, 0xdd);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final String KEY = "finish-key-0001";
  private static final String TRACE = "trace-42";

  private FakeWorld world;
  private FinishDeparture finish;
  private Departure running;
  private final Caller person =
      new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, false));
    finish = new FinishDeparture(world, world, world, new DepartureViews(world, world, world), world, world, Clock.fixed(NOW, ZoneOffset.UTC));
    running = put(1, DepartureStatus.SCHEDULED, TENANT).start(ACTUAL_START, STARTER, NOW);
    world.departures.put(running.id(), running);
  }

  private Departure put(int n, DepartureStatus status, UUID tenant) {
    Departure created = new Departure(new UUID(0, 1000 + n), tenant, String.format("SAL-%03d", n), TOUR, START, START.plus(Duration.ofHours(8)),
        "America/Bogota", status, ACCOUNT, START.minus(Duration.ofDays(2)));
    world.departures.put(created.id(), created);
    return created;
  }

  private FinishDeparture.Command command(String code, String key, Instant at, String cause) {
    return new FinishDeparture.Command(person, code, key, at, cause, TRACE);
  }

  @Test
  void A_the_end_is_recorded_and_the_event_says_it_was_not_an_interruption() {
    FinishDeparture.Finished result = finish.execute(command("SAL-001", KEY, NOW.minus(Duration.ofMinutes(5)), null));
    assertThat(result.recorded()).isTrue();
    Departure stored = world.departures.get(running.id());
    assertThat(stored.status()).isEqualTo(DepartureStatus.FINISHED);
    assertThat(stored.execution().actualEnd()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
    assertThat(stored.execution().finishedBy()).isEqualTo(ACCOUNT);
    assertThat(stored.execution().startedBy()).isEqualTo(STARTER);
    assertThat(result.view().departure()).isEqualTo(stored);
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaFinalized");
      assertThat(event.aggregateId()).isEqualTo(running.id());
      assertThat(event.actorId()).isEqualTo(ACCOUNT);
      assertThat(event.payload()).containsEntry("interrupted", false).containsEntry("actualEnd", NOW.minus(Duration.ofMinutes(5)).toString());
    });
  }

  @Test
  void A_an_interruption_ends_the_departure_finished_with_its_cause_and_the_event_omits_the_cause() {
    finish.execute(command("SAL-001", KEY, NOW, "Heavy rain closed the trail"));
    Departure stored = world.departures.get(running.id());
    assertThat(stored.status()).isEqualTo(DepartureStatus.FINISHED);
    assertThat(stored.execution().interrupted()).isTrue();
    assertThat(stored.execution().interruptionCause()).isEqualTo("Heavy rain closed the trail");
    assertThat(world.events.get(0).payload()).containsEntry("interrupted", true).doesNotContainValue("Heavy rain closed the trail");
  }

  @Test
  void A_the_same_key_answers_the_same_departure_and_writes_nothing_more() {
    finish.execute(command("SAL-001", KEY, NOW.minus(Duration.ofMinutes(5)), null));
    FinishDeparture.Finished again = finish.execute(command("SAL-001", KEY, NOW, "Another"));
    assertThat(again.recorded()).isFalse();
    assertThat(again.view().departure().execution().interrupted()).isFalse();
    assertThat(world.events).hasSize(1);
  }

  @Test
  void A_an_end_before_the_start_or_in_the_future_is_refused_and_nothing_is_written() {
    assertThatThrownBy(() -> finish.execute(command("SAL-001", KEY, ACTUAL_START.minusSeconds(1), null))).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> finish.execute(command("SAL-001", KEY, NOW.plusSeconds(1), null))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.departures.get(running.id())).isEqualTo(running);
    assertThat(world.events).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_an_interruption_cause_that_is_blank_or_too_long_is_refused_by_field() {
    for (String bad : new String[] {"", "  ", "x".repeat(1001)}) {
      assertThatThrownBy(() -> finish.execute(command("SAL-001", KEY, NOW, bad)))
          .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("cause"));
    }
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_departure_that_is_not_in_execution_is_refused_as_a_transition() {
    for (DepartureStatus other : new DepartureStatus[] {DepartureStatus.SCHEDULED, DepartureStatus.FINISHED, DepartureStatus.CANCELLED}) {
      put(2, other, TENANT);
      assertThatThrownBy(() -> finish.execute(command("SAL-002", KEY, NOW, null))).as(other.name()).isInstanceOf(InvalidStatusTransitionException.class);
    }
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_the_state_is_judged_again_after_the_lock() {
    world.onLock = () -> world.departures.put(running.id(), running.finish(NOW, STARTER, NOW, null));
    assertThatThrownBy(() -> finish.execute(command("SAL-001", KEY, NOW, null))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_an_unknown_departure_or_one_of_another_tenant_is_not_found() {
    assertThatThrownBy(() -> finish.execute(command("SAL-099", KEY, NOW, null))).isInstanceOf(DepartureNotFoundException.class);
    put(3, DepartureStatus.IN_EXECUTION, OTHER_TENANT);
    assertThatThrownBy(() -> finish.execute(command("SAL-003", KEY, NOW, null))).isInstanceOf(DepartureNotFoundException.class);
  }

  @Test
  void A_finish_with_reservations_or_the_catalog_down_writes_nothing() {
    world.reservationsDown = true;
    assertThatThrownBy(() -> finish.execute(command("SAL-001", KEY, NOW, null))).isInstanceOf(DependencyUnavailableException.class);
    world.reservationsDown = false;
    world.catalogDown = true;
    assertThatThrownBy(() -> finish.execute(command("SAL-001", KEY, NOW, null))).isInstanceOf(DependencyUnavailableException.class);
    assertThat(world.departures.get(running.id())).isEqualTo(running);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_failed_event_leaves_the_departure_and_the_key_as_they_were() {
    world.outboxFails = true;
    assertThatThrownBy(() -> finish.execute(command("SAL-001", KEY, NOW, null))).isInstanceOf(IllegalStateException.class);
    assertThat(world.departures.get(running.id())).isEqualTo(running);
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_the_request_is_judged_in_a_fixed_order_a_caller_that_is_not_one_before_the_key_before_the_time() {
    Caller stranger = new Caller("not-a-uuid", TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));
    assertThatThrownBy(() -> finish.execute(new FinishDeparture.Command(stranger, "SAL-001", "short", null, null, TRACE)))
        .isInstanceOf(co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException.class);
    assertThatThrownBy(() -> finish.execute(command("SAL-001", "short", null, null)))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("Idempotency-Key"));
  }

  @Test
  void A_a_bad_key_or_a_missing_time_is_refused_by_field() {
    assertThatThrownBy(() -> finish.execute(command("SAL-001", "short", NOW, null)))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("Idempotency-Key"));
    assertThatThrownBy(() -> finish.execute(command("SAL-001", KEY, null, null)))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("finishedAt"));
  }

  @Test
  void A_two_ends_with_the_same_key_answer_the_winner_to_the_loser() {
    Departure winner = running.finish(NOW.minus(Duration.ofMinutes(20)), ACCOUNT, NOW, null);
    world.onLock = () -> {
      world.departures.put(running.id(), winner);
      world.keys.put(TENANT + "/finishDeparture/" + KEY, running.id());
    };
    FinishDeparture.Finished loser = finish.execute(command("SAL-001", KEY, NOW.minus(Duration.ofMinutes(10)), "Rain"));
    assertThat(loser.recorded()).isFalse();
    assertThat(loser.view().departure()).isEqualTo(winner);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_key_registered_an_instant_ago_answers_the_departure_it_was_registered_for() {
    world.racedDeparture = running.id();
    FinishDeparture.Finished loser = finish.execute(command("SAL-001", KEY, NOW, null));
    assertThat(loser.recorded()).isFalse();
    assertThat(loser.view().departure().id()).isEqualTo(running.id());
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_key_used_for_another_departure_is_refused_and_not_answered_with_that_one() {
    Departure other = put(2, DepartureStatus.SCHEDULED, TENANT).start(ACTUAL_START, STARTER, NOW);
    world.departures.put(other.id(), other);
    finish.execute(command("SAL-002", KEY, NOW, null));
    assertThatThrownBy(() -> finish.execute(command("SAL-001", KEY, NOW, null))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.departures.get(running.id())).isEqualTo(running);
  }
}
