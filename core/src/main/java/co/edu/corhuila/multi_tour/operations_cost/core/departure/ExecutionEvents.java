package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The events that tell commercial-reservation a departure started or ended (ADR-004 D4). The payload is the minimum that
 * service reads. The cause of an interruption is free text written by a person and travels nowhere: only whether it happened.
 */
final class ExecutionEvents {

  private ExecutionEvents() {}

  static OutboxEvent started(UUID eventId, Departure departure, UUID account, String trace, Instant at) {
    Map<String, Object> payload = base(departure);
    payload.put("actualStart", departure.execution().actualStart().toString());
    return new OutboxEvent(eventId, "SalidaExecutionStarted", departure.id(), departure.tenantId(), account, trace, payload, at);
  }

  static OutboxEvent finalized(UUID eventId, Departure departure, UUID account, String trace, Instant at) {
    Map<String, Object> payload = base(departure);
    payload.put("actualEnd", departure.execution().actualEnd().toString());
    payload.put("interrupted", departure.execution().interrupted());
    return new OutboxEvent(eventId, "SalidaFinalized", departure.id(), departure.tenantId(), account, trace, payload, at);
  }

  /** The mark is made by the system, not by a person: the event has no actor. */
  static OutboxEvent markedPending(UUID eventId, Departure departure, String trace, Instant at) {
    Map<String, Object> payload = base(departure);
    payload.put("code", departure.code());
    payload.put("estimatedEnd", departure.estimatedEnd().toString());
    payload.put("markedAt", departure.execution().pendingMarkedAt().toString());
    return new OutboxEvent(eventId, "SalidaMarkedPendienteDeRegularizacion", departure.id(), departure.tenantId(), null, trace, payload, at);
  }

  private static Map<String, Object> base(Departure departure) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", departure.tenantId().toString());
    payload.put("salidaId", departure.id().toString());
    return payload;
  }
}
