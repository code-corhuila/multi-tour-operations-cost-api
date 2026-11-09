package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The departure with no framework and no double (RN-OPS-API-10, -11, -18). */
class DepartureTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.plus(Duration.ofDays(10));
  private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");

  private static Departure scheduled(Instant start, Instant end) {
    return Departure.schedule(ID, TENANT, "SAL-001", TOUR, start, end, "America/Bogota", ACCOUNT, NOW);
  }

  private static Departure inState(DepartureStatus status) {
    return new Departure(ID, TENANT, "SAL-001", TOUR, START, START.plus(Duration.ofHours(8)), "America/Bogota", status,
        ACCOUNT, NOW);
  }

  @Test
  void D_start_alert_only_while_scheduled_and_past_the_start() {
    assertThat(inState(DepartureStatus.SCHEDULED).startAlert(START.minusSeconds(1))).isFalse();
    assertThat(inState(DepartureStatus.SCHEDULED).startAlert(START)).isTrue();
    assertThat(inState(DepartureStatus.SCHEDULED).startAlert(START.plus(Duration.ofDays(1)))).isTrue();
    for (DepartureStatus status : new DepartureStatus[] {DepartureStatus.IN_EXECUTION, DepartureStatus.FINISHED, DepartureStatus.CANCELLED}) {
      assertThat(inState(status).startAlert(START.plus(Duration.ofDays(1)))).as(status.name()).isFalse();
    }
  }

  private static final Instant END = START.plus(Duration.ofHours(8));

  @Test
  void D_the_mark_does_not_change_the_state() {
    for (DepartureStatus status : new DepartureStatus[] {DepartureStatus.SCHEDULED, DepartureStatus.IN_EXECUTION}) {
      Departure open = status == DepartureStatus.SCHEDULED ? inState(status) : inState(DepartureStatus.SCHEDULED).start(START, ACCOUNT, START);
      Departure marked = open.markPending(END.plusSeconds(1));
      assertThat(marked.status()).isEqualTo(status);
      assertThat(marked.execution().pendingRegularization()).isTrue();
      assertThat(marked.execution().pendingMarkedAt()).isEqualTo(END.plusSeconds(1));
      assertThat(open.execution().pendingRegularization()).isFalse();
    }
  }

  @Test
  void D_a_departure_is_marked_only_when_open_unmarked_and_past_its_end() {
    assertThatThrownBy(() -> inState(DepartureStatus.SCHEDULED).markPending(END.minusSeconds(1))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(inState(DepartureStatus.SCHEDULED).markPending(END).execution().pendingRegularization()).isTrue();
    assertThatThrownBy(() -> inState(DepartureStatus.FINISHED).markPending(END.plusSeconds(1))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThatThrownBy(() -> inState(DepartureStatus.CANCELLED).markPending(END.plusSeconds(1))).isInstanceOf(InvalidStatusTransitionException.class);
    Departure marked = inState(DepartureStatus.SCHEDULED).markPending(END.plusSeconds(1));
    assertThatThrownBy(() -> marked.markPending(END.plusSeconds(2))).isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void D_starting_or_finishing_a_marked_departure_clears_the_mark_and_keeps_its_moment() {
    Instant markedAt = END.plusSeconds(1);
    Departure started = inState(DepartureStatus.SCHEDULED).markPending(markedAt).start(START.plusSeconds(60), ACCOUNT, markedAt.plusSeconds(5));
    assertThat(started.execution().pendingRegularization()).isFalse();
    assertThat(started.execution().pendingMarkedAt()).isEqualTo(markedAt);
    Departure finished = started.finish(markedAt.plusSeconds(2), ACCOUNT, markedAt.plusSeconds(5), null);
    assertThat(finished.execution().pendingRegularization()).isFalse();
    assertThat(finished.execution().pendingMarkedAt()).isEqualTo(markedAt);
    Departure runningThenMarked = inState(DepartureStatus.SCHEDULED).start(START, ACCOUNT, START).markPending(markedAt);
    assertThat(runningThenMarked.finish(markedAt.plusSeconds(1), ACCOUNT, markedAt.plusSeconds(5), null).execution().pendingRegularization()).isFalse();
  }

  @Test
  void D_born_scheduled_with_all_fields() {
    Departure departure = scheduled(START, START.plus(Duration.ofHours(8)));
    assertThat(departure.status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(departure.id()).isEqualTo(ID);
    assertThat(departure.tenantId()).isEqualTo(TENANT);
    assertThat(departure.code()).isEqualTo("SAL-001");
    assertThat(departure.serviceId()).isEqualTo(TOUR);
    assertThat(departure.start()).isEqualTo(START);
    assertThat(departure.estimatedEnd()).isEqualTo(START.plus(Duration.ofHours(8)));
    assertThat(departure.timeZone()).isEqualTo("America/Bogota");
    assertThat(departure.scheduledBy()).isEqualTo(ACCOUNT);
    assertThat(departure.scheduledAt()).isEqualTo(NOW);
  }

  @Test
  void D_end_before_start_is_refused_and_names_the_field() {
    assertThatThrownBy(() -> scheduled(START, START.minusSeconds(1)))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("scheduledEnd"));
  }

  @Test
  void D_end_equal_to_start_and_end_on_a_later_day_are_accepted() {
    assertThat(scheduled(START, START).estimatedEnd()).isEqualTo(START);
    assertThat(scheduled(START, START.plus(Duration.ofDays(3))).estimatedEnd()).isEqualTo(START.plus(Duration.ofDays(3)));
  }

  @Test
  void D_start_in_the_past_is_refused() {
    assertThatThrownBy(() -> scheduled(NOW.minusSeconds(1), NOW.plusSeconds(60)))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(scheduled(NOW, NOW.plusSeconds(60)).start()).isEqualTo(NOW);
  }

  @Test
  void D_a_missing_field_is_refused_and_names_it() {
    assertThatThrownBy(() -> Departure.schedule(ID, TENANT, "SAL-001", null, START, START, "America/Bogota", ACCOUNT, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("serviceId"));
    assertThatThrownBy(() -> Departure.schedule(ID, TENANT, "SAL-001", TOUR, null, START, "America/Bogota", ACCOUNT, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("scheduledStart"));
    assertThatThrownBy(() -> Departure.schedule(ID, TENANT, "SAL-001", TOUR, START, null, "America/Bogota", ACCOUNT, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("scheduledEnd"));
    assertThatThrownBy(() -> Departure.schedule(ID, TENANT, "SAL-001", TOUR, START, START, " ", ACCOUNT, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("timeZone"));
  }

  @Test
  void D_a_time_zone_that_does_not_exist_is_refused() {
    assertThatThrownBy(() -> Departure.schedule(ID, TENANT, "SAL-001", TOUR, START, START, "Mars/Olympus", ACCOUNT, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("timeZone"));
  }

  @Test
  void D_reschedule_changes_start_and_end_and_keeps_code_and_state() {
    Departure departure = scheduled(START, START.plus(Duration.ofHours(8)));
    Instant newStart = START.plus(Duration.ofDays(1));
    Departure changed = departure.reschedule(newStart, newStart.plus(Duration.ofHours(6)), NOW);
    assertThat(changed.start()).isEqualTo(newStart);
    assertThat(changed.estimatedEnd()).isEqualTo(newStart.plus(Duration.ofHours(6)));
    assertThat(changed.code()).isEqualTo("SAL-001");
    assertThat(changed.id()).isEqualTo(ID);
    assertThat(changed.status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(departure.start()).as("the departure is immutable").isEqualTo(START);
  }

  @Test
  void D_a_marked_departure_that_is_rescheduled_is_no_longer_pending_and_keeps_the_moment_of_its_mark() {
    Departure marked = inState(DepartureStatus.SCHEDULED).markPending(END.plusSeconds(1));
    Instant newStart = END.plus(Duration.ofDays(2));
    Departure changed = marked.reschedule(newStart, newStart.plus(Duration.ofHours(6)), END.plusSeconds(5));
    assertThat(changed.execution().pendingRegularization()).isFalse();
    assertThat(changed.execution().pendingMarkedAt()).isEqualTo(END.plusSeconds(1));
  }

  private static Departure markedScheduled() {
    return inState(DepartureStatus.SCHEDULED).markPending(END.plusSeconds(1));
  }

  @Test
  void D_regularize_as_executed_finishes_the_departure_with_the_real_hours_and_who_and_when() {
    Instant now = END.plus(Duration.ofHours(1));
    Departure done = markedScheduled().regularize(START.plusSeconds(60), END.minusSeconds(60), null, null, ACCOUNT, now);
    assertThat(done.status()).isEqualTo(DepartureStatus.FINISHED);
    DepartureExecution run = done.execution();
    assertThat(run.actualStart()).isEqualTo(START.plusSeconds(60));
    assertThat(run.actualEnd()).isEqualTo(END.minusSeconds(60));
    assertThat(run.startedBy()).isEqualTo(ACCOUNT);
    assertThat(run.finishedBy()).isEqualTo(ACCOUNT);
    assertThat(run.interrupted()).isFalse();
    assertThat(run.pendingRegularization()).isFalse();
    assertThat(run.pendingMarkedAt()).isEqualTo(END.plusSeconds(1));
    assertThat(run.regularizedBy()).isEqualTo(ACCOUNT);
    assertThat(run.regularizedAt()).isEqualTo(now);
    assertThat(run.earlyStartReason()).isNull();
  }

  @Test
  void D_regularize_as_interrupted_keeps_the_cause_and_still_finishes() {
    Departure done = markedScheduled().regularize(START, END, "Heavy rain", null, ACCOUNT, END.plus(Duration.ofHours(1)));
    assertThat(done.status()).isEqualTo(DepartureStatus.FINISHED);
    assertThat(done.execution().interrupted()).isTrue();
    assertThat(done.execution().interruptionCause()).isEqualTo("Heavy rain");
    assertThatThrownBy(() -> markedScheduled().regularize(START, END, " ", null, ACCOUNT, END.plus(Duration.ofHours(1))))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("cause"));
  }

  @Test
  void D_an_early_start_needs_its_reason_and_a_reason_needs_an_early_start() {
    Instant now = END.plus(Duration.ofHours(1));
    Instant early = START.minusSeconds(600);
    assertThatThrownBy(() -> markedScheduled().regularize(early, END, null, null, ACCOUNT, now))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("earlyStartReason"));
    assertThatThrownBy(() -> markedScheduled().regularize(early, END, null, " ", ACCOUNT, now)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> markedScheduled().regularize(early, END, null, "x".repeat(501), ACCOUNT, now)).isInstanceOf(InvalidDataException.class);
    assertThat(markedScheduled().regularize(early, END, null, "Left early because of the weather", ACCOUNT, now).execution().earlyStartReason())
        .isEqualTo("Left early because of the weather");
    assertThat(markedScheduled().regularize(early, END, null, "x".repeat(500), ACCOUNT, now).execution().actualStart()).isEqualTo(early);
    assertThatThrownBy(() -> markedScheduled().regularize(START, END, null, "Not needed", ACCOUNT, now))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("earlyStartReason"));
  }

  @Test
  void D_the_real_hours_are_never_in_the_future_nor_out_of_order() {
    Instant now = END.plus(Duration.ofHours(1));
    assertThatThrownBy(() -> markedScheduled().regularize(now.plusSeconds(1), now.plusSeconds(2), null, null, ACCOUNT, now))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> markedScheduled().regularize(START, START.minusSeconds(1), null, null, ACCOUNT, now))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> markedScheduled().regularize(START, now.plusSeconds(1), null, null, ACCOUNT, now))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(markedScheduled().regularize(START, START, null, null, ACCOUNT, now).execution().actualEnd()).isEqualTo(START);
    assertThatThrownBy(() -> markedScheduled().regularize(null, END, null, null, ACCOUNT, now))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("actualStart"));
    assertThatThrownBy(() -> markedScheduled().regularize(START, null, null, null, ACCOUNT, now))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("actualEnd"));
  }

  @Test
  void D_regularize_only_from_scheduled_and_marked() {
    Instant now = END.plus(Duration.ofHours(1));
    assertThatThrownBy(() -> inState(DepartureStatus.SCHEDULED).regularize(START, END, null, null, ACCOUNT, now))
        .isInstanceOf(InvalidStatusTransitionException.class);
    Departure runningMarked = inState(DepartureStatus.SCHEDULED).start(START, ACCOUNT, START).markPending(END.plusSeconds(1));
    assertThatThrownBy(() -> runningMarked.regularize(START, END, null, null, ACCOUNT, now)).isInstanceOf(InvalidStatusTransitionException.class);
    for (DepartureStatus status : new DepartureStatus[] {DepartureStatus.FINISHED, DepartureStatus.CANCELLED}) {
      assertThatThrownBy(() -> inState(status).regularize(START, END, null, null, ACCOUNT, now)).isInstanceOf(InvalidStatusTransitionException.class);
    }
  }

  @Test
  void D_reschedule_only_when_scheduled() {
    for (DepartureStatus status : new DepartureStatus[] {DepartureStatus.IN_EXECUTION, DepartureStatus.FINISHED, DepartureStatus.CANCELLED}) {
      assertThatThrownBy(() -> inState(status).reschedule(START.plusSeconds(60), START.plusSeconds(120), NOW))
          .as(status.name())
          .isInstanceOf(InvalidStatusTransitionException.class);
    }
  }

  @Test
  void D_reschedule_applies_the_same_checks_as_scheduling() {
    Departure departure = scheduled(START, START.plus(Duration.ofHours(8)));
    assertThatThrownBy(() -> departure.reschedule(START, START.minusSeconds(1), NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("scheduledEnd"));
    assertThatThrownBy(() -> departure.reschedule(NOW.minusSeconds(1), NOW.plusSeconds(60), NOW))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void D_setting_the_same_values_is_no_change() {
    Departure departure = scheduled(START, START.plus(Duration.ofHours(8)));
    assertThat(departure.hasSchedule(START, START.plus(Duration.ofHours(8)))).isTrue();
    assertThat(departure.hasSchedule(START.plusSeconds(1), START.plus(Duration.ofHours(8)))).isFalse();
    assertThat(departure.reschedule(START, START.plus(Duration.ofHours(8)), NOW)).isEqualTo(departure);
  }

  @Test
  void D_a_departure_needs_its_identity() {
    assertThatThrownBy(() -> new Departure(null, TENANT, "SAL-001", TOUR, START, START, "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new Departure(ID, null, "SAL-001", TOUR, START, START, "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new Departure(ID, TENANT, "SAL-1", TOUR, START, START, "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
  }

  @Test
  void D_a_state_other_than_scheduled_refuses_even_the_same_values() {
    Departure running = inState(DepartureStatus.IN_EXECUTION);
    assertThatThrownBy(() -> running.reschedule(running.start(), running.estimatedEnd(), NOW))
        .isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void D_the_same_values_on_a_scheduled_departure_are_no_change_even_if_its_start_has_passed() {
    Departure departure = scheduled(NOW, NOW.plusSeconds(60));
    Instant later = NOW.plus(Duration.ofDays(1));
    assertThat(departure.reschedule(NOW, NOW.plusSeconds(60), later)).isEqualTo(departure);
  }

  @Test
  void D_reschedule_with_a_missing_value_is_refused_and_names_it() {
    Departure departure = scheduled(START, START.plusSeconds(60));
    assertThatThrownBy(() -> departure.reschedule(null, START, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("scheduledStart"));
    assertThatThrownBy(() -> departure.reschedule(START, null, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("scheduledEnd"));
    assertThatThrownBy(() -> departure.reschedule(START, START, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void D_only_an_iana_zone_of_up_to_64_characters_is_accepted() {
    for (String bad : new String[] {"+05:00", "UTC+5", "Z", "America/" + "x".repeat(60)}) {
      assertThatThrownBy(() -> Departure.schedule(ID, TENANT, "SAL-001", TOUR, START, START, bad, ACCOUNT, NOW))
          .as(bad).isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("timeZone"));
    }
    assertThat(Departure.schedule(ID, TENANT, "SAL-001", TOUR, START, START, "Europe/Madrid", ACCOUNT, NOW).timeZone())
        .isEqualTo("Europe/Madrid");
  }

  @Test
  void D_the_code_is_SAL_and_three_to_six_digits() {
    for (String bad : new String[] {"SAL-12", "SAL-1234567", "sal-001", "SAL-00A", " SAL-001"}) {
      assertThatThrownBy(() -> new Departure(ID, TENANT, bad, TOUR, START, START, "America/Bogota",
          DepartureStatus.SCHEDULED, ACCOUNT, NOW)).as(bad).isInstanceOf(InvalidDataException.class);
    }
    assertThat(new Departure(ID, TENANT, "SAL-123456", TOUR, START, START, "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, NOW).code()).isEqualTo("SAL-123456");
  }

  @Test
  void D_state_who_and_when_are_mandatory() {
    assertThatThrownBy(() -> new Departure(ID, TENANT, "SAL-001", TOUR, START, START, "America/Bogota", null, ACCOUNT, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("status"));
    assertThatThrownBy(() -> new Departure(ID, TENANT, "SAL-001", TOUR, START, START, "America/Bogota",
        DepartureStatus.SCHEDULED, null, NOW))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("scheduledBy"));
    assertThatThrownBy(() -> new Departure(ID, TENANT, "SAL-001", TOUR, START, START, "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, null))
        .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("scheduledAt"));
  }

  @Test
  void D_instants_keep_microseconds_as_the_database_does() {
    Instant withNanos = START.plusNanos(123_456_789);
    Departure departure = scheduled(withNanos, withNanos.plusSeconds(60));
    assertThat(departure.start()).isEqualTo(START.plusNanos(123_456_000));
    assertThat(departure.estimatedEnd()).isEqualTo(START.plusSeconds(60).plusNanos(123_456_000));
  }
}
