package co.edu.corhuila.multi_tour.operations_cost.core.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * An event of the outbox as job 7 reads it (ADR-004 D4). This service is the only one that changes it: it is published when
 * every consumer took it and failed when its attempts ran out. Instants keep the microseconds the database keeps.
 */
public record StoredEvent(
    UUID eventId, String eventType, int eventVersion, UUID tenantId, UUID aggregateId, UUID actorId, String reason,
    Map<String, Object> payload, String correlationId, Instant occurredAt, Instant publishedAt, Status status, int attempts,
    String lastError, Instant nextAttemptAt) {

  public enum Status {
    PENDING,
    PUBLISHED,
    FAILED
  }

  /** Published, whatever it was: a delivery that did succeed is true even if the event had been given up. */
  public StoredEvent published(Instant at) {
    if (status == Status.PUBLISHED) {
      return this;
    }
    return new StoredEvent(eventId, eventType, eventVersion, tenantId, aggregateId, actorId, reason, payload, correlationId,
        occurredAt, at.truncatedTo(ChronoUnit.MICROS), Status.PUBLISHED, attempts, lastError, nextAttemptAt);
  }

  /** One more failed delivery; an event no longer pending is not touched by a report that came late. */
  public StoredEvent withFailedAttempt(String error, Instant at, RetryPolicy policy) {
    if (status != Status.PENDING) {
      return this;
    }
    Instant now = at.truncatedTo(ChronoUnit.MICROS);
    int count = attempts + 1;
    return new StoredEvent(eventId, eventType, eventVersion, tenantId, aggregateId, actorId, reason, payload, correlationId,
        occurredAt, publishedAt, count >= policy.maxAttempts() ? Status.FAILED : Status.PENDING, count, error,
        now.plus(policy.waitAfter(count)));
  }
}
