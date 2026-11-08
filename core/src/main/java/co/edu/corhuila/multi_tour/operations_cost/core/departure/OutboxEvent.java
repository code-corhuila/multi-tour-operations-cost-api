package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** An event that waits to be delivered (ADR-004 D4). The payload carries no personal data. */
public record OutboxEvent(UUID eventId, String eventType, UUID aggregateId, UUID tenantId, Map<String, Object> payload, Instant occurredAt) {}
