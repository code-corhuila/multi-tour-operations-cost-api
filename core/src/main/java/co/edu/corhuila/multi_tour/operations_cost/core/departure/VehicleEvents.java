package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The event that tells commercial-reservation the new total capacity of the vehicles of a departure (ADR-004 D4). The payload is
 * the minimum that service reads: it computes the effective capacity itself from the capacity of the tour and this total.
 */
final class VehicleEvents {

  private VehicleEvents() {}

  static OutboxEvent changed(UUID eventId, Departure departure, int vehicleTotalCapacity, UUID account, String trace, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", departure.tenantId().toString());
    payload.put("salidaId", departure.id().toString());
    payload.put("vehicleTotalCapacity", vehicleTotalCapacity);
    return new OutboxEvent(eventId, "SalidaVehiclesChanged", departure.id(), departure.tenantId(), account, trace, payload, at);
  }
}
