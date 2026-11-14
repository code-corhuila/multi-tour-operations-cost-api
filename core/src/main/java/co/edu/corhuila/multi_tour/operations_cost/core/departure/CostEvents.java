package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The event that tells the other domains a real operational cost was recorded (ADR-004 D4). The payload is the minimum a consumer needs;
 * the concept and the justification are free text written by a person and travel nowhere.
 */
final class CostEvents {

  private CostEvents() {}

  static OutboxEvent recorded(UUID eventId, OperationalCost cost, String trace, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", cost.tenantId().toString());
    payload.put("salidaId", cost.departureId().toString());
    payload.put("costId", cost.costId().toString());
    payload.put("amountCents", cost.amountCents());
    payload.put("mode", cost.mode().name());
    payload.put("withoutParametrization", cost.withoutParametrization());
    return new OutboxEvent(eventId, "OperationalCostRecorded", cost.departureId(), cost.tenantId(), cost.recordedBy(), trace, payload, at);
  }
}
