package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The two events of HU-OPS-003 carry the minimum commercial-reservation reads and no personal data (api 004 D-05). */
class ExecutionEventsTest {

  private static final Instant START = Instant.parse("2026-11-10T08:00:00Z");
  private static final Instant NOW = START.plus(Duration.ofMinutes(10));
  private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-0000000000dd");
  private static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-0000000000e1");

  private static Departure scheduled() {
    return new Departure(ID, TENANT, "SAL-001", UUID.randomUUID(), START, START.plus(Duration.ofHours(8)), "America/Bogota",
        DepartureStatus.SCHEDULED, ACTOR, START.minus(Duration.ofDays(3)));
  }

  @Test
  void D_started_event_carries_tenant_departure_and_the_real_start() {
    Departure started = scheduled().start(START.plusSeconds(30), ACTOR, NOW);
    OutboxEvent event = ExecutionEvents.started(EVENT, started, ACTOR, "trace-1", NOW);
    assertThat(event.eventType()).isEqualTo("SalidaExecutionStarted");
    assertThat(event.aggregateId()).isEqualTo(ID);
    assertThat(event.tenantId()).isEqualTo(TENANT);
    assertThat(event.eventId()).isEqualTo(EVENT);
    assertThat(event.actorId()).isEqualTo(ACTOR);
    assertThat(event.correlationId()).isEqualTo("trace-1");
    assertThat(event.occurredAt()).isEqualTo(NOW);
    assertThat(event.payload()).containsOnlyKeys("tenantId", "salidaId", "actualStart");
    assertThat(event.payload()).containsEntry("tenantId", TENANT.toString()).containsEntry("salidaId", ID.toString())
        .containsEntry("actualStart", START.plusSeconds(30).toString());
  }

  @Test
  void D_finalized_event_carries_the_real_end_and_whether_it_was_interrupted_but_not_the_cause() {
    Departure running = scheduled().start(START.plusSeconds(30), ACTOR, NOW);
    OutboxEvent normal = ExecutionEvents.finalized(EVENT, running.finish(NOW, ACTOR, NOW, null), ACTOR, "trace-2", NOW);
    assertThat(normal.eventType()).isEqualTo("SalidaFinalized");
    assertThat(normal.payload()).containsOnlyKeys("tenantId", "salidaId", "actualEnd", "interrupted");
    assertThat(normal.payload()).containsEntry("actualEnd", NOW.toString()).containsEntry("interrupted", false);
    OutboxEvent cut = ExecutionEvents.finalized(EVENT, running.finish(NOW, ACTOR, NOW, "Rain"), ACTOR, "trace-3", NOW);
    assertThat(cut.payload()).containsEntry("interrupted", true).doesNotContainValue("Rain");
  }
}
