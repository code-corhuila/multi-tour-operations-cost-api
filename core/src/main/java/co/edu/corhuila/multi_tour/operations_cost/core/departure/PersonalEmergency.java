package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.UUID;

/**
 * The personal emergency of one reservation of a departure in execution (HU-OPS-008, api 011 RN-OPS-API-91): the reservation, how many of
 * its travelers cannot continue, what happened, who recorded it and when. The reservation belongs to commercial-reservation and is kept as
 * its id only; whether {@code affected} exceeds its travelers is judged by the use case, which reads them. It is a value, recorded once and
 * never rewritten. The detail is free text that can name health facts, so it is never sent in an event.
 */
public record PersonalEmergency(UUID reservationId, int affected, String detail, UUID recordedBy, Instant recordedAt) {

  private static final int DETAIL_MAX = 1000;

  public PersonalEmergency {
    if (reservationId == null || recordedBy == null || recordedAt == null) {
      throw new InvalidDataException("reservationId", "An emergency needs its reservation, who recorded it and when");
    }
    if (affected < 1) {
      throw new InvalidDataException("affected", "At least one traveler is affected");
    }
    // the limit is the one of the contract, counted on the text as sent; the record keeps it stripped
    if (detail == null || onlySpace(detail) || detail.length() > DETAIL_MAX) {
      throw new InvalidDataException("detail", "The detail has 1 to " + DETAIL_MAX + " characters");
    }
    detail = detail.strip();
  }

  /** Blank by the standard of Java and by the one of Unicode too: a non-breaking space is not white space to {@code isBlank}. */
  private static boolean onlySpace(String text) {
    return text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
  }

  /** The detail can name health facts: it is never printed, so an assertion or a log line cannot leak it. */
  @Override
  public String toString() {
    return "PersonalEmergency[reservationId=" + reservationId + ", affected=" + affected + ", recordedBy=" + recordedBy + ", recordedAt=" + recordedAt + "]";
  }
}
