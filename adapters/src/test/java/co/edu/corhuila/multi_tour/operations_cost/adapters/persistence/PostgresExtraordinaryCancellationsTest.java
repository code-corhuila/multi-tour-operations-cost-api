package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ExtraordinaryCancellation;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdempotencyKeys;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The extraordinary cancellations of the reservations of a departure, over the real engine (api spec 009, RN-OPS-API-73 to -75). */
class PostgresExtraordinaryCancellationsTest extends PostgresTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final UUID RESERVATION_1 = UUID.randomUUID();
  private static final UUID RESERVATION_2 = UUID.randomUUID();
  private static final UUID RECORDER = UUID.fromString("00000000-0000-4000-8000-0000000000dd");

  private static JdbcExtraordinaryCancellations cancellations() {
    return new JdbcExtraordinaryCancellations(jdbc);
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

  private static ExtraordinaryCancellation cancellation(UUID reservation, String justification) {
    return new ExtraordinaryCancellation(reservation, justification, RECORDER, NOW);
  }

  @Test
  void I_cancellation_event_and_key_are_written_together() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "ExtraordinaryCancellationRecorded", departure.id(), tenant, RECORDER, "trace-9",
        Map.of("reservationId", RESERVATION_1.toString()), NOW);
    transaction.run(() -> {
      cancellations().add(tenant, departure.id(), cancellation(RESERVATION_1, "a medical emergency"));
      outbox.add(event);
      keys.register(tenant, IdempotencyKeys.RECORD_EXTRAORDINARY_CANCELLATION, "cancel-key-01", departure.id());
      return null;
    });
    assertThat(cancellations().cancellationsOf(tenant, departure.id())).containsExactly(cancellation(RESERVATION_1, "a medical emergency"));
    assertThat(keys.departureOf(tenant, IdempotencyKeys.RECORD_EXTRAORDINARY_CANCELLATION, "cancel-key-01")).contains(departure.id());
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
  }

  @Test
  void I_a_failure_after_the_cancellation_leaves_neither_cancellation_nor_key() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    assertThatThrownBy(() -> transaction.run(() -> {
      cancellations().add(tenant, departure.id(), cancellation(RESERVATION_1, "x"));
      keys.register(tenant, IdempotencyKeys.RECORD_EXTRAORDINARY_CANCELLATION, "cancel-key-02", departure.id());
      throw new IllegalStateException("the outbox is down");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(cancellations().cancellationsOf(tenant, departure.id())).isEmpty();
    assertThat(keys.departureOf(tenant, IdempotencyKeys.RECORD_EXTRAORDINARY_CANCELLATION, "cancel-key-02")).isEmpty();
  }

  @Test
  void I_a_reservation_has_one_cancellation() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      cancellations().add(tenant, departure.id(), cancellation(RESERVATION_1, "first"));
      return null;
    });
    assertThat(cancellations().exists(tenant, departure.id(), RESERVATION_1)).isTrue();
    assertThat(cancellations().exists(tenant, departure.id(), RESERVATION_2)).isFalse();
    assertThatThrownBy(() -> transaction.run(() -> {
      cancellations().add(tenant, departure.id(), cancellation(RESERVATION_1, "second"));
      return null;
    })).isInstanceOf(DuplicateKeyException.class);
    assertThat(cancellations().cancellationsOf(tenant, departure.id())).containsExactly(cancellation(RESERVATION_1, "first"));
  }

  @Test
  void I_who_recorded_the_cancellation_and_when_are_kept() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      cancellations().add(tenant, departure.id(), cancellation(RESERVATION_1, "an emergency"));
      return null;
    });
    var row = jdbc.sql("SELECT recorded_by, recorded_at FROM salida_extraordinary_cancellation WHERE salida_id = :id AND reservation_id = :reservation")
        .param("id", departure.id()).param("reservation", RESERVATION_1).query().singleRow();
    assertThat(row.get("recorded_by")).isEqualTo(RECORDER);
    assertThat(((java.sql.Timestamp) row.get("recorded_at")).toInstant()).isEqualTo(NOW);
  }

  @Test
  void I_another_tenant_sees_nothing_and_cannot_write_on_the_departure() {
    UUID tenant = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      cancellations().add(tenant, departure.id(), cancellation(RESERVATION_1, "an emergency"));
      return null;
    });
    assertThat(cancellations().cancellationsOf(stranger, departure.id())).isEmpty();
    assertThat(cancellations().exists(stranger, departure.id(), RESERVATION_1)).isFalse();
    assertThatThrownBy(() -> transaction.run(() -> {
      cancellations().add(stranger, departure.id(), cancellation(RESERVATION_2, "x"));
      return null;
    })).isInstanceOf(Exception.class);
  }
}
