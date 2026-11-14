package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Cancellation;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdempotencyKeys;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The cancellation of a departure over the real engine (api spec 010, RN-OPS-API-81, -85 and -86). */
class PostgresCancellationTest extends PostgresTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final UUID PERSON = UUID.fromString("00000000-0000-4000-8000-0000000000dd");

  private static Departure scheduled(UUID tenant, Instant start) {
    Departure created = new Departure(UUID.randomUUID(), tenant, departures.nextCode(tenant), TOUR, start, start.plus(Duration.ofHours(8)),
        "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, start.minus(Duration.ofDays(2)));
    departures.save(created);
    return created;
  }

  @Test
  void I_a_cancellation_by_the_system_is_stored_and_read_back() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant, NOW.plus(Duration.ofDays(3)));
    Departure cancelled = created.cancel(Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER, null, null, NOW);
    assertThat(departures.updateExecution(created, cancelled)).isTrue();
    Departure read = departures.find(tenant, created.id()).orElseThrow();
    assertThat(read).isEqualTo(cancelled);
    assertThat(read.status()).isEqualTo(DepartureStatus.CANCELLED);
    assertThat(read.execution().cancellation()).isEqualTo(new Cancellation(NOW, null, Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER, null));
  }

  @Test
  void I_the_cancellation_keeps_its_instant_who_and_cause() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant, NOW.minus(Duration.ofDays(2)));
    Departure marked = created.markPending(NOW);
    assertThat(departures.updateExecution(created, marked)).isTrue();
    Departure cancelled = marked.cancel(Cancellation.Cause.NOT_CARRIED_OUT, "nobody came to the meeting point", PERSON, NOW);
    assertThat(departures.updateExecution(marked, cancelled)).isTrue();
    Departure read = departures.find(tenant, created.id()).orElseThrow();
    assertThat(read.execution().cancellation()).isEqualTo(new Cancellation(NOW, PERSON, Cancellation.Cause.NOT_CARRIED_OUT, "nobody came to the meeting point"));
    assertThat(read.execution().pendingRegularization()).isFalse();
    assertThat(read.execution().pendingMarkedAt()).isNotNull();
  }

  @Test
  void I_cancellation_event_and_key_are_written_together() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant, NOW.plus(Duration.ofDays(3)));
    Departure cancelled = created.cancel(Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER, null, null, NOW);
    OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "SalidaCancelled", created.id(), tenant, null, "trace-3", Map.of("salidaId", created.id().toString()), NOW);
    transaction.run(() -> {
      assertThat(departures.updateExecution(created, cancelled)).isTrue();
      keys.register(tenant, IdempotencyKeys.CANCEL_DEPARTURE, "cancel-key-01", created.id());
      outbox.add(event);
      return null;
    });
    assertThat(departures.find(tenant, created.id()).orElseThrow().status()).isEqualTo(DepartureStatus.CANCELLED);
    assertThat(keys.departureOf(tenant, IdempotencyKeys.CANCEL_DEPARTURE, "cancel-key-01")).contains(created.id());
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
  }

  @Test
  void I_a_failure_after_the_cancellation_leaves_the_departure_scheduled() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant, NOW.plus(Duration.ofDays(3)));
    Departure cancelled = created.cancel(Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER, null, null, NOW);
    assertThatThrownBy(() -> transaction.run(() -> {
      departures.updateExecution(created, cancelled);
      throw new IllegalStateException("the outbox is down");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(departures.find(tenant, created.id()).orElseThrow().status()).isEqualTo(DepartureStatus.SCHEDULED);
  }

  @Test
  void I_a_second_cancellation_from_a_stale_reading_changes_nothing() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant, NOW.plus(Duration.ofDays(3)));
    Departure first = created.cancel(Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER, null, null, NOW);
    Departure second = created.cancel(Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER, null, PERSON, NOW.plusSeconds(60));
    assertThat(departures.updateExecution(created, first)).isTrue();
    assertThat(departures.updateExecution(created, second)).isFalse();
    assertThat(departures.find(tenant, created.id()).orElseThrow().execution().cancellation().at()).isEqualTo(NOW);
  }

  @Test
  void I_another_tenant_cannot_cancel_the_departure_and_the_engine_refuses_to_rewrite_a_cancellation() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant, NOW.plus(Duration.ofDays(3)));
    Departure stranger = new Departure(created.id(), UUID.randomUUID(), created.code(), TOUR, created.start(), created.estimatedEnd(), created.timeZone(),
        DepartureStatus.SCHEDULED, ACCOUNT, created.scheduledAt());
    assertThat(departures.updateExecution(stranger, stranger.cancel(Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER, null, null, NOW))).isFalse();
    assertThat(departures.find(tenant, created.id()).orElseThrow().status()).isEqualTo(DepartureStatus.SCHEDULED);

    Departure cancelled = created.cancel(Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER, null, null, NOW);
    assertThat(departures.updateExecution(created, cancelled)).isTrue();
    assertThatThrownBy(() -> jdbc.sql("UPDATE salida SET cancelled_at = cancelled_at + interval '1 minute' WHERE salida_id = :id").param("id", created.id()).update())
        .hasMessageContaining("a cancellation is final");
  }
}
