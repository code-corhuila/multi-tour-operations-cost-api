package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-OPS-006 (api spec 010), RN-OPS-API-80, -82 and -88: the cancellation of a departure and the states that admit it. */
class CancellationTest {

  private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
  private static final Instant START = NOW.plus(Duration.ofDays(3));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final Cancellation.Cause SYSTEM_CAUSE = Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER;

  private static Departure scheduled() {
    return new Departure(new UUID(0, 1001), TENANT, "SAL-001", TOUR, START, START.plus(Duration.ofHours(8)), "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, NOW.minus(Duration.ofDays(2)));
  }

  /** A departure whose estimated end passed and that nobody started: the one a person may regularize as not performed. */
  private static Departure markedAndPast() {
    Instant start = NOW.minus(Duration.ofDays(2));
    Departure past = new Departure(new UUID(0, 1002), TENANT, "SAL-002", TOUR, start, start.plus(Duration.ofHours(8)), "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, start.minus(Duration.ofDays(2)));
    return past.markPending(NOW);
  }

  @Test
  void D_cause_and_reason_agree() {
    assertThatCode(() -> new Cancellation(NOW, null, SYSTEM_CAUSE, null)).doesNotThrowAnyException();
    assertThatCode(() -> new Cancellation(NOW, ACCOUNT, Cancellation.Cause.NOT_CARRIED_OUT, "nobody came")).doesNotThrowAnyException();
    // a reason belongs to a departure that did not take place, and a departure that did not take place needs it
    assertThatThrownBy(() -> new Cancellation(NOW, ACCOUNT, SYSTEM_CAUSE, "a reason")).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new Cancellation(NOW, ACCOUNT, Cancellation.Cause.NOT_CARRIED_OUT, null)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new Cancellation(NOW, ACCOUNT, Cancellation.Cause.NOT_CARRIED_OUT, "  ")).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new Cancellation(NOW, ACCOUNT, Cancellation.Cause.NOT_CARRIED_OUT, "r".repeat(501)))
        .isInstanceOf(InvalidDataException.class);
    assertThatCode(() -> new Cancellation(NOW, ACCOUNT, Cancellation.Cause.NOT_CARRIED_OUT, "r".repeat(500))).doesNotThrowAnyException();
    assertThatThrownBy(() -> new Cancellation(null, ACCOUNT, SYSTEM_CAUSE, null)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new Cancellation(NOW, ACCOUNT, null, null)).isInstanceOf(InvalidDataException.class);
  }

  @Test
  void D_only_a_scheduled_departure_is_cancelled() {
    Departure cancelled = scheduled().cancel(SYSTEM_CAUSE, null, ACCOUNT, NOW);
    assertThat(cancelled.status()).isEqualTo(DepartureStatus.CANCELLED);
    assertThat(cancelled.execution().cancellation()).isEqualTo(new Cancellation(NOW, ACCOUNT, SYSTEM_CAUSE, null));
    Departure running = scheduled().start(START.plus(Duration.ofMinutes(5)), ACCOUNT, START.plus(Duration.ofMinutes(5)));
    assertThatThrownBy(() -> running.cancel(SYSTEM_CAUSE, null, ACCOUNT, NOW)).isInstanceOf(InvalidStatusTransitionException.class);
    Departure finished = running.finish(START.plus(Duration.ofHours(2)), ACCOUNT, START.plus(Duration.ofHours(2)), null);
    assertThatThrownBy(() -> finished.cancel(SYSTEM_CAUSE, null, ACCOUNT, NOW)).isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void D_a_cancelled_departure_stays_cancelled() {
    Departure cancelled = scheduled().cancel(SYSTEM_CAUSE, null, ACCOUNT, NOW);
    assertThatThrownBy(() -> cancelled.cancel(SYSTEM_CAUSE, null, ACCOUNT, NOW.plusSeconds(60))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThatThrownBy(() -> cancelled.reschedule(START, START.plus(Duration.ofHours(9)), NOW)).isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void D_not_performed_needs_a_marked_never_started_departure_and_its_cause() {
    Departure cancelled = markedAndPast().cancel(Cancellation.Cause.NOT_CARRIED_OUT, "nobody came", ACCOUNT, NOW);
    assertThat(cancelled.status()).isEqualTo(DepartureStatus.CANCELLED);
    assertThat(cancelled.execution().pendingRegularization()).isFalse();
    assertThat(cancelled.execution().cancellation().reason()).isEqualTo("nobody came");
    // a departure that is not marked is not one that did not take place
    assertThatThrownBy(() -> scheduled().cancel(Cancellation.Cause.NOT_CARRIED_OUT, "nobody came", ACCOUNT, NOW))
        .isInstanceOf(InvalidStatusTransitionException.class);
    assertThatThrownBy(() -> markedAndPast().cancel(Cancellation.Cause.NOT_CARRIED_OUT, null, ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
  }
}
