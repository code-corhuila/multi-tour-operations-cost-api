package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.UUID;

/**
 * The extraordinary cancellation of one reservation of a departure in execution (HU-OPS-005, api 009 RN-OPS-API-71): the reservation, the
 * justification of the emergency, who recorded it and when. The reservation belongs to commercial-reservation and is kept as its id only.
 * It is a value, recorded once and never rewritten.
 */
public record ExtraordinaryCancellation(UUID reservationId, String justification, UUID recordedBy, Instant recordedAt) {

  private static final int JUSTIFICATION_MAX = 500;

  public ExtraordinaryCancellation {
    if (reservationId == null || recordedBy == null || recordedAt == null) {
      throw new InvalidDataException("reservationId", "A cancellation needs its reservation, who recorded it and when");
    }
    if (justification == null || justification.isBlank() || justification.length() > JUSTIFICATION_MAX) {
      throw new InvalidDataException("justification", "The justification has 1 to " + JUSTIFICATION_MAX + " characters");
    }
  }
}
