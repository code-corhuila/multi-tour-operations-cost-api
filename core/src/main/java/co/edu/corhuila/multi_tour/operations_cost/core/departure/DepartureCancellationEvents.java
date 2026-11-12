package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The event that tells commercial-reservation and the audit that a departure was cancelled (HU-OPS-006, ADR-004 D4). The payload is the
 * minimum they read: the tenant, the departure, the instant, the cause and, when a person asked, who. The written reason of a departure
 * that was not carried out is free text and does not travel.
 */
final class DepartureCancellationEvents {

  private DepartureCancellationEvents() {}

  static OutboxEvent cancelled(UUID eventId, Departure departure, String trace, Instant at) {
    Cancellation cancellation = departure.execution().cancellation();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", departure.tenantId().toString());
    payload.put("salidaId", departure.id().toString());
    payload.put("cancelledAt", cancellation.at().toString());
    payload.put("cause", cancellation.cause().name());
    if (cancellation.by() != null) {
      payload.put("requestedBy", cancellation.by().toString());
    }
    return new OutboxEvent(eventId, "SalidaCancelled", departure.id(), departure.tenantId(), cancellation.by(), trace, payload, at);
  }
}
