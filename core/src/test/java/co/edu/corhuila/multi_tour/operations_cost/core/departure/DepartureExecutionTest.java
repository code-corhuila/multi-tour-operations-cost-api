package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The start, the end and the interruption of a departure with no framework and no double (RN-OPS-API-40 to -43, -44). */
class DepartureExecutionTest {

  private static final Instant SCHEDULED_START = Instant.parse("2026-11-10T08:00:00Z");
  private static final Instant SCHEDULED_END = SCHEDULED_START.plus(Duration.ofHours(8));
  private static final Instant NOW = SCHEDULED_START.plus(Duration.ofMinutes(10));
  private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final UUID SCHEDULER = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID STARTER = UUID.fromString("00000000-0000-4000-8000-0000000000dd");
  private static final UUID FINISHER = UUID.fromString("00000000-0000-4000-8000-0000000000ee");

  private static Departure inState(DepartureStatus status) {
    return new Departure(ID, TENANT, "SAL-001", TOUR, SCHEDULED_START, SCHEDULED_END, "America/Bogota", status, SCHEDULER,
        SCHEDULED_START.minus(Duration.ofDays(3)));
  }

  private static Departure scheduled() {
    return inState(DepartureStatus.SCHEDULED);
  }

  private static Departure running() {
    return scheduled().start(SCHEDULED_START.plusSeconds(60), STARTER, NOW);
  }

  @Test
  void D_start_only_from_scheduled() {
    Departure started = scheduled().start(SCHEDULED_START.plusSeconds(60), STARTER, NOW);
    assertThat(started.status()).isEqualTo(DepartureStatus.IN_EXECUTION);
    assertThat(started.execution().actualStart()).isEqualTo(SCHEDULED_START.plusSeconds(60));
    assertThat(started.execution().startedBy()).isEqualTo(STARTER);
    assertThat(started.execution().actualEnd()).isNull();
    for (DepartureStatus other : new DepartureStatus[] {DepartureStatus.IN_EXECUTION, DepartureStatus.FINISHED, DepartureStatus.CANCELLED}) {
      assertThatThrownBy(() -> inState(other).start(SCHEDULED_START.plusSeconds(60), STARTER, NOW))
          .isInstanceOf(InvalidStatusTransitionException.class);
    }
  }

  @Test
  void D_start_before_the_schedule_is_refused() {
    assertThatThrownBy(() -> scheduled().start(SCHEDULED_START.minusSeconds(1), STARTER, NOW))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(scheduled().start(SCHEDULED_START, STARTER, NOW).status()).isEqualTo(DepartureStatus.IN_EXECUTION);
  }

  @Test
  void D_start_in_the_future_is_refused() {
    assertThatThrownBy(() -> scheduled().start(NOW.plusSeconds(1), STARTER, NOW)).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(scheduled().start(NOW, STARTER, NOW).status()).isEqualTo(DepartureStatus.IN_EXECUTION);
  }

  @Test
  void D_the_state_is_judged_before_the_time() {
    assertThatThrownBy(() -> inState(DepartureStatus.FINISHED).start(SCHEDULED_START.minus(Duration.ofDays(1)), STARTER, NOW))
        .isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void D_finish_only_from_in_execution() {
    Departure finished = running().finish(NOW, FINISHER, NOW, null);
    assertThat(finished.status()).isEqualTo(DepartureStatus.FINISHED);
    assertThat(finished.execution().actualEnd()).isEqualTo(NOW);
    assertThat(finished.execution().finishedBy()).isEqualTo(FINISHER);
    assertThat(finished.execution().interrupted()).isFalse();
    assertThat(finished.execution().interruptionCause()).isNull();
    assertThat(finished.execution().startedBy()).isEqualTo(STARTER);
    for (DepartureStatus other : new DepartureStatus[] {DepartureStatus.SCHEDULED, DepartureStatus.FINISHED, DepartureStatus.CANCELLED}) {
      assertThatThrownBy(() -> inState(other).finish(NOW, FINISHER, NOW, null)).isInstanceOf(InvalidStatusTransitionException.class);
    }
  }

  @Test
  void D_end_before_the_start_is_refused() {
    Departure started = scheduled().start(SCHEDULED_START.plusSeconds(60), STARTER, NOW);
    assertThatThrownBy(() -> started.finish(SCHEDULED_START.plusSeconds(59), FINISHER, NOW, null))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(started.finish(SCHEDULED_START.plusSeconds(60), FINISHER, NOW, null).status()).isEqualTo(DepartureStatus.FINISHED);
  }

  @Test
  void D_end_in_the_future_is_refused() {
    assertThatThrownBy(() -> running().finish(NOW.plusSeconds(1), FINISHER, NOW, null)).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void D_interruption_finishes_with_its_cause() {
    Departure interrupted = running().finish(NOW, FINISHER, NOW, "Heavy rain closed the trail");
    assertThat(interrupted.status()).isEqualTo(DepartureStatus.FINISHED);
    assertThat(interrupted.execution().interrupted()).isTrue();
    assertThat(interrupted.execution().interruptionCause()).isEqualTo("Heavy rain closed the trail");
  }

  @Test
  void D_an_interruption_cause_is_1_to_1000_characters() {
    for (String bad : new String[] {"", "   ", "x".repeat(1001)}) {
      assertThatThrownBy(() -> running().finish(NOW, FINISHER, NOW, bad))
          .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("cause"));
    }
    assertThat(running().finish(NOW, FINISHER, NOW, "x".repeat(1000)).execution().interrupted()).isTrue();
  }

  @Test
  void D_the_times_keep_the_microseconds_the_database_keeps() {
    Departure started = scheduled().start(SCHEDULED_START.plusSeconds(60).plusNanos(1_234_567), STARTER, NOW);
    assertThat(started.execution().actualStart()).isEqualTo(SCHEDULED_START.plusSeconds(60).plusNanos(1_234_000));
  }

  @Test
  void D_a_departure_that_never_ran_has_an_empty_execution() {
    assertThat(scheduled().execution()).isEqualTo(DepartureExecution.NONE);
    assertThat(scheduled().execution().interrupted()).isFalse();
  }

  @Test
  void D_finishing_judges_the_state_before_the_time_and_the_cause() {
    assertThatThrownBy(() -> scheduled().finish(NOW.plusSeconds(1), FINISHER, NOW, ""))
        .isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void D_the_end_keeps_the_microseconds_the_database_keeps() {
    Departure finished = running().finish(NOW.minusNanos(1_000).plusNanos(234), FINISHER, NOW, null);
    assertThat(finished.execution().actualEnd()).isEqualTo(NOW.minusNanos(1_000));
  }

  @Test
  void D_a_start_or_an_end_without_its_time_or_its_person_is_refused_by_field() {
    assertThatThrownBy(() -> scheduled().start(null, STARTER, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("startedAt"));
    assertThatThrownBy(() -> scheduled().start(NOW, null, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("startedBy"));
    assertThatThrownBy(() -> running().finish(null, FINISHER, NOW, null))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("finishedAt"));
    assertThatThrownBy(() -> running().finish(NOW, null, NOW, null))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("finishedBy"));
  }
}
