package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An event that waits to be delivered (ADR-004 D4). The payload carries the minimum its reader needs and no personal data, except the written justification of an extraordinary cancellation (HU-OPS-005); the actor and the correlation id
 * travel apart, as the table keeps them.
 */
public record OutboxEvent(
    UUID eventId, String eventType, UUID aggregateId, UUID tenantId, UUID actorId, String correlationId,
    Map<String, Object> payload, Instant occurredAt) {}
