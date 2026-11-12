package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-006 (api spec 010), AC-API-10.1 to -10.6: the cancellation that the saga asks for, ports as fakes. */
class CancelDepartureTest {

  private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
  private static final Instant START = NOW.plus(Duration.ofDays(3));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final String KEY = "cancel-dep-key-1";
  private static final String TRACE = "trace-88";
  private static final Cancellation.Cause CAUSE = Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER;

  private FakeWorld world;
  private CancelDeparture cancel;
  private Departure scheduled;

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    cancel = new CancelDeparture(world, world, world, world, world, Clock.fixed(NOW, ZoneOffset.UTC));
    scheduled = departure(1, DepartureStatus.SCHEDULED, START);
  }

  private Departure departure(int n, DepartureStatus status, Instant start) {
    Departure created = new Departure(new UUID(0, 1000 + n), TENANT, String.format("SAL-%03d", n), TOUR, start, start.plus(Duration.ofHours(8)),
        "America/Bogota", status, ACCOUNT, start.minus(Duration.ofDays(5)));
    world.departures.put(created.id(), created);
    return created;
  }

  private CancelDeparture.Command command(String code, String key, UUID requestedBy, Role role) {
    return new CancelDeparture.Command(TENANT, code, key, CAUSE, requestedBy, role, TRACE);
  }

  @Test
  void A_a_scheduled_departure_is_cancelled_and_its_instant_answered() {
    CancelDeparture.Cancelled result = cancel.execute(command("SAL-001", KEY, ACCOUNT, Role.TENANT_ADMINISTRATOR));
    assertThat(result.cancelledNow()).isTrue();
    assertThat(result.departureId()).isEqualTo(scheduled.id());
    assertThat(result.cancelledAt()).isEqualTo(NOW);
    Departure stored = world.departures.get(scheduled.id());
    assertThat(stored.status()).isEqualTo(DepartureStatus.CANCELLED);
    assertThat(stored.execution().cancellation()).isEqualTo(new Cancellation(NOW, ACCOUNT, CAUSE, null));
    assertThat(world.keys).hasSize(1);
  }

  @Test
  void A_the_event_carries_tenant_departure_instant_cause_and_who() {
    cancel.execute(command("SAL-001", KEY, ACCOUNT, Role.TENANT_ADMINISTRATOR));
    assertThat(world.events).hasSize(1);
    OutboxEvent event = world.events.get(0);
    assertThat(event.eventType()).isEqualTo("SalidaCancelled");
    assertThat(event.aggregateId()).isEqualTo(scheduled.id());
    assertThat(event.tenantId()).isEqualTo(TENANT);
    assertThat(event.correlationId()).isEqualTo(TRACE);
    assertThat(event.payload()).containsEntry("tenantId", TENANT.toString()).containsEntry("salidaId", scheduled.id().toString())
        .containsEntry("cancelledAt", NOW.toString()).containsEntry("cause", "NOT_ATTRIBUTABLE_TO_CUSTOMER")
        .containsEntry("requestedBy", ACCOUNT.toString());
  }

  @Test
  void A_asking_again_answers_the_original_instant_and_writes_nothing() {
    cancel.execute(command("SAL-001", KEY, ACCOUNT, Role.TENANT_ADMINISTRATOR));
    CancelDeparture later = new CancelDeparture(world, world, world, world, world, Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));
    CancelDeparture.Cancelled again = later.execute(command("SAL-001", "another-key-77", null, null));
    assertThat(again.cancelledNow()).isFalse();
    assertThat(again.cancelledAt()).isEqualTo(NOW);
    assertThat(world.events).hasSize(1);
    assertThat(cancel.execute(command("SAL-001", KEY, ACCOUNT, Role.TENANT_ADMINISTRATOR)).cancelledNow()).isFalse();
  }

  @Test
  void A_a_started_or_finished_departure_is_refused() {
    Departure running = departure(2, DepartureStatus.SCHEDULED, NOW.minus(Duration.ofHours(1))).start(NOW.minus(Duration.ofMinutes(30)), ACCOUNT, NOW);
    world.departures.put(running.id(), running);
    assertThatThrownBy(() -> cancel.execute(command("SAL-002", KEY, null, null))).isInstanceOf(InvalidStatusTransitionException.class);
    Departure finished = running.finish(NOW, ACCOUNT, NOW, null);
    world.departures.put(finished.id(), finished);
    assertThatThrownBy(() -> cancel.execute(command("SAL-002", KEY, null, null))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.events).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_a_collaborator_cannot_cancel_a_future_departure() {
    assertThatThrownBy(() -> cancel.execute(command("SAL-001", KEY, ACCOUNT, Role.OPERATIONAL_COLLABORATOR))).isInstanceOf(CallerNotAllowedException.class);
    assertThat(world.departures.get(scheduled.id()).status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_the_administrator_can_and_a_collaborator_can_when_the_departure_is_marked() {
    assertThat(cancel.execute(command("SAL-001", KEY, ACCOUNT, Role.TENANT_ADMINISTRATOR)).cancelledNow()).isTrue();
    Departure past = departure(3, DepartureStatus.SCHEDULED, NOW.minus(Duration.ofDays(2))).markPending(NOW);
    world.departures.put(past.id(), past);
    assertThat(cancel.execute(command("SAL-003", "marked-key-01", ACCOUNT, Role.OPERATIONAL_COLLABORATOR)).cancelledNow()).isTrue();
  }

  @Test
  void A_with_no_person_the_system_cancels_and_the_event_names_nobody() {
    cancel.execute(command("SAL-001", KEY, null, null));
    assertThat(world.departures.get(scheduled.id()).execution().cancellation().by()).isNull();
    assertThat(world.events.get(0).payload()).doesNotContainKey("requestedBy");
  }

  @Test
  void A_an_inactive_tenant_does_not_stop_the_cancellation_and_nobody_is_asked() {
    Departure past = departure(4, DepartureStatus.SCHEDULED, NOW.minus(Duration.ofHours(3)));
    world.departures.put(past.id(), past);
    assertThat(cancel.execute(command("SAL-004", KEY, null, null)).cancelledNow()).isTrue();
    assertThat(world.catalogCalls).isZero();
    assertThat(world.reservationCalls).isZero();
  }

  @Test
  void A_the_departure_of_another_tenant_is_not_found() {
    assertThatThrownBy(() -> cancel.execute(new CancelDeparture.Command(new UUID(0, 0xab), "SAL-001", KEY, CAUSE, null, null, TRACE)))
        .isInstanceOf(DepartureNotFoundException.class);
  }

  @Test
  void A_another_cause_or_a_bad_key_is_refused_before_anything_is_read() {
    assertThatThrownBy(() -> cancel.execute(new CancelDeparture.Command(TENANT, "SAL-001", KEY, Cancellation.Cause.NOT_CARRIED_OUT, null, null, TRACE)))
        .isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> cancel.execute(new CancelDeparture.Command(TENANT, "SAL-001", KEY, null, null, null, TRACE)))
        .isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> cancel.execute(command("SAL-001", "short", null, null))).isInstanceOf(InvalidDataException.class);
    assertThat(world.departures.get(scheduled.id()).status()).isEqualTo(DepartureStatus.SCHEDULED);
  }

  @Test
  void A_a_key_used_for_another_departure_is_refused() {
    departure(5, DepartureStatus.SCHEDULED, START.plus(Duration.ofDays(1)));
    cancel.execute(command("SAL-001", KEY, null, null));
    assertThatThrownBy(() -> cancel.execute(command("SAL-005", KEY, null, null))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.departures.get(new UUID(0, 1005)).status()).isEqualTo(DepartureStatus.SCHEDULED);
  }

  @Test
  void A_a_down_outbox_rolls_the_cancellation_and_the_key_back() {
    world.outboxFails = true;
    assertThatThrownBy(() -> cancel.execute(command("SAL-001", KEY, ACCOUNT, Role.TENANT_ADMINISTRATOR))).isInstanceOf(IllegalStateException.class);
    assertThat(world.departures.get(scheduled.id()).status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(world.keys).isEmpty();
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_departure_started_while_the_lock_was_taken_is_refused_and_nothing_is_written() {
    Departure past = departure(6, DepartureStatus.SCHEDULED, NOW.minus(Duration.ofHours(1)));
    world.onLock = () -> world.departures.put(past.id(), past.start(NOW.minus(Duration.ofMinutes(30)), ACCOUNT, NOW));
    assertThatThrownBy(() -> cancel.execute(command("SAL-006", KEY, null, null))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.keys).isEmpty();
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_any_role_that_is_not_the_administrator_nor_a_collaborator_of_a_marked_departure_is_refused() {
    for (Role role : new Role[] {Role.FINAL_CUSTOMER, Role.MANAGER, Role.ACCOUNTANT, Role.ANALYST, Role.PLATFORM_ADMINISTRATOR, Role.SERVICE}) {
      assertThatThrownBy(() -> cancel.execute(command("SAL-001", KEY, ACCOUNT, role))).isInstanceOf(CallerNotAllowedException.class);
    }
    assertThat(world.departures.get(scheduled.id()).status()).isEqualTo(DepartureStatus.SCHEDULED);
  }

  @Test
  void A_the_state_is_judged_before_the_role() {
    Departure running = departure(7, DepartureStatus.SCHEDULED, NOW.minus(Duration.ofHours(1))).start(NOW.minus(Duration.ofMinutes(30)), ACCOUNT, NOW);
    world.departures.put(running.id(), running);
    assertThatThrownBy(() -> cancel.execute(command("SAL-007", KEY, ACCOUNT, Role.OPERATIONAL_COLLABORATOR)))
        .isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void A_a_cancellation_that_wins_the_lock_is_answered_with_its_original_instant() {
    Departure winner = scheduled.cancel(CAUSE, null, null, NOW.minusSeconds(30));
    world.onLock = () -> world.departures.put(scheduled.id(), winner);
    CancelDeparture.Cancelled result = cancel.execute(command("SAL-001", KEY, null, null));
    assertThat(result.cancelledNow()).isFalse();
    assertThat(result.cancelledAt()).isEqualTo(NOW.minusSeconds(30));
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_key_taken_for_another_departure_by_a_request_in_parallel_is_refused() {
    world.racedDeparture = new UUID(0, 4242);
    assertThatThrownBy(() -> cancel.execute(command("SAL-001", KEY, null, null))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.departures.get(scheduled.id()).status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(world.events).isEmpty();
  }
}
