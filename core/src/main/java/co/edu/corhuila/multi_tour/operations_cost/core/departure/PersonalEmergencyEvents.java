package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The event that tells commercial-reservation and the audit that someone of a reservation in execution cannot continue for a personal
 * emergency (HU-OPS-008, ADR-004 D4). It carries how many travelers are affected so the owner of the money calculates the refund; the
 * free text of the detail never travels, because it can name health facts (RN-OPS-API-97).
 */
final class PersonalEmergencyEvents {

  private PersonalEmergencyEvents() {}

  static OutboxEvent recorded(UUID eventId, Departure departure, PersonalEmergency emergency, String trace, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", departure.tenantId().toString());
    payload.put("salidaId", departure.id().toString());
    payload.put("reservationId", emergency.reservationId().toString());
    payload.put("affected", emergency.affected());
    payload.put("recordedBy", emergency.recordedBy().toString());
    payload.put("recordedAt", emergency.recordedAt().toString());
    return new OutboxEvent(eventId, "PersonalEmergencyRecorded", departure.id(), departure.tenantId(), emergency.recordedBy(), trace, payload, at);
  }
}
