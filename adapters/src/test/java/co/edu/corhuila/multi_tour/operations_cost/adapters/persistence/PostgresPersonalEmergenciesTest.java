package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdempotencyKeys;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.PersonalEmergency;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The personal emergencies of the reservations of a departure, over the real engine (api spec 011, RN-OPS-API-93 to -95). */
class PostgresPersonalEmergenciesTest extends PostgresTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final UUID RESERVATION_1 = UUID.randomUUID();
  private static final UUID RESERVATION_2 = UUID.randomUUID();
  private static final UUID RECORDER = UUID.fromString("00000000-0000-4000-8000-0000000000dd");

  private static JdbcPersonalEmergencies emergencies() {
    return new JdbcPersonalEmergencies(jdbc);
  }

  private static Departure running(UUID tenant) {
    Instant start = NOW.minus(Duration.ofHours(3));
    Departure created = new Departure(UUID.randomUUID(), tenant, departures.nextCode(tenant), TOUR, start, start.plus(Duration.ofHours(8)),
        "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, start.minus(Duration.ofDays(2)));
    departures.save(created);
    Departure started = created.start(NOW.minus(Duration.ofHours(2)), RECORDER, NOW);
    departures.updateExecution(created, started);
    return started;
  }

  private static PersonalEmergency emergency(UUID reservation, int affected, String detail) {
    return new PersonalEmergency(reservation, affected, detail, RECORDER, NOW);
  }

  @Test
  void I_emergency_event_and_key_are_written_together() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "PersonalEmergencyRecorded", departure.id(), tenant, RECORDER, "trace-9",
        Map.of("reservationId", RESERVATION_1.toString()), NOW);
    transaction.run(() -> {
      emergencies().add(tenant, departure.id(), emergency(RESERVATION_1, 2, "a traveler fainted"));
      outbox.add(event);
      keys.register(tenant, IdempotencyKeys.RECORD_PERSONAL_EMERGENCY, "emergency-key-01", departure.id());
      return null;
    });
    assertThat(emergencies().emergenciesOf(tenant, departure.id())).containsExactly(emergency(RESERVATION_1, 2, "a traveler fainted"));
    assertThat(keys.departureOf(tenant, IdempotencyKeys.RECORD_PERSONAL_EMERGENCY, "emergency-key-01")).contains(departure.id());
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
  }

  @Test
  void I_a_failure_after_the_emergency_leaves_neither_emergency_nor_key() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    assertThatThrownBy(() -> transaction.run(() -> {
      emergencies().add(tenant, departure.id(), emergency(RESERVATION_1, 1, "x"));
      keys.register(tenant, IdempotencyKeys.RECORD_PERSONAL_EMERGENCY, "emergency-key-02", departure.id());
      throw new IllegalStateException("the outbox is down");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(emergencies().emergenciesOf(tenant, departure.id())).isEmpty();
    assertThat(keys.departureOf(tenant, IdempotencyKeys.RECORD_PERSONAL_EMERGENCY, "emergency-key-02")).isEmpty();
  }

  @Test
  void I_a_reservation_has_one_emergency() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      emergencies().add(tenant, departure.id(), emergency(RESERVATION_1, 2, "first"));
      return null;
    });
    assertThat(emergencies().hasEmergency(tenant, departure.id(), RESERVATION_1)).isTrue();
    assertThat(emergencies().hasEmergency(tenant, departure.id(), RESERVATION_2)).isFalse();
    assertThatThrownBy(() -> transaction.run(() -> {
      emergencies().add(tenant, departure.id(), emergency(RESERVATION_1, 1, "second"));
      return null;
    })).isInstanceOf(DuplicateKeyException.class);
    assertThat(emergencies().emergenciesOf(tenant, departure.id())).containsExactly(emergency(RESERVATION_1, 2, "first"));
  }

  @Test
  void I_who_recorded_the_emergency_and_when_are_kept() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      emergencies().add(tenant, departure.id(), emergency(RESERVATION_1, 3, "an emergency"));
      return null;
    });
    var row = jdbc.sql("SELECT recorded_by, recorded_at, affected_travelers FROM salida_personal_emergency WHERE salida_id = :id AND reservation_id = :reservation")
        .param("id", departure.id()).param("reservation", RESERVATION_1).query().singleRow();
    assertThat(row.get("recorded_by")).isEqualTo(RECORDER);
    assertThat(((java.sql.Timestamp) row.get("recorded_at")).toInstant()).isEqualTo(NOW);
    assertThat(row.get("affected_travelers")).isEqualTo(3);
  }

  @Test
  void I_another_tenant_sees_nothing_and_cannot_write_on_the_departure() {
    UUID tenant = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      emergencies().add(tenant, departure.id(), emergency(RESERVATION_1, 1, "an emergency"));
      return null;
    });
    assertThat(emergencies().emergenciesOf(stranger, departure.id())).isEmpty();
    assertThat(emergencies().hasEmergency(stranger, departure.id(), RESERVATION_1)).isFalse();
    assertThatThrownBy(() -> transaction.run(() -> {
      emergencies().add(stranger, departure.id(), emergency(RESERVATION_2, 1, "x"));
      return null;
    })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).isNotInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void I_the_detail_is_stored_stripped_up_to_1000_and_the_engine_refuses_zero_travelers() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      emergencies().add(tenant, departure.id(), emergency(RESERVATION_1, 1, "  padded  "));
      emergencies().add(tenant, departure.id(), emergency(RESERVATION_2, 1, "d".repeat(1000)));
      return null;
    });
    assertThat(emergencies().emergenciesOf(tenant, departure.id())).extracting(PersonalEmergency::detail).containsExactlyInAnyOrder("padded", "d".repeat(1000));
    assertThatThrownBy(() -> jdbc.sql(
            "INSERT INTO salida_personal_emergency (tenant_id, salida_id, reservation_id, affected_travelers, detail, recorded_by, recorded_at) "
                + "VALUES (:tenant, :departure, :reservation, 0, 'x', :by, now())")
        .param("tenant", tenant).param("departure", departure.id()).param("reservation", UUID.randomUUID()).param("by", RECORDER).update())
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .isNotInstanceOf(DuplicateKeyException.class);
  }
}
