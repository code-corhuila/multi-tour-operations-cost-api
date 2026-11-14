package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The event that tells commercial-reservation and the audit that a reservation in execution was cancelled for an emergency (HU-OPS-005,
 * ADR-004 D4). The justification is free text of at most 500 characters that the person wrote and the event carries it, as the domain
 * events catalog lists it.
 */
final class CancellationEvents {

  private CancellationEvents() {}

  static OutboxEvent recorded(UUID eventId, Departure departure, ExtraordinaryCancellation cancellation, String trace, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", departure.tenantId().toString());
    payload.put("salidaId", departure.id().toString());
    payload.put("reservationId", cancellation.reservationId().toString());
    payload.put("justification", cancellation.justification());
    payload.put("recordedBy", cancellation.recordedBy().toString());
    return new OutboxEvent(eventId, "ExtraordinaryCancellationRecorded", departure.id(), departure.tenantId(), cancellation.recordedBy(), trace, payload, at);
  }
}
