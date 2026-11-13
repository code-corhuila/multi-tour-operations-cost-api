package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Outbox;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Writes the event in the table of the schema, on the connection of the transaction that writes the change (ADR-004 D4). */
public final class JdbcOutbox implements Outbox {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcOutbox(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void add(OutboxEvent event) {
    String payload;
    try {
      payload = json.writeValueAsString(event.payload());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("The payload of the event cannot be written", e);
    }
    jdbc.sql("""
            INSERT INTO outbox_event (outbox_event_id, tenant_id, event_type, aggregate_id, actor_id, correlation_id, payload,
                                      occurred_at)
            VALUES (:id, :tenant, :type, :aggregate, :actor, :trace, CAST(:payload AS jsonb), :at)
            """)
        .param("id", event.eventId())
        .param("tenant", event.tenantId())
        .param("type", event.eventType())
        .param("aggregate", event.aggregateId())
        .param("actor", event.actorId())
        .param("trace", event.correlationId())
        .param("payload", payload)
        .param("at", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
        .update();
  }
}
