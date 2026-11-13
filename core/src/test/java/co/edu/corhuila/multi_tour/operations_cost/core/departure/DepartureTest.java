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
