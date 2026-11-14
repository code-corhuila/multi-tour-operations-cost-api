package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.Collection;
import java.util.UUID;

/**
 * What one reservation of a departure received (HU-OPS-004, RN-OPS-API-61 to -63, -65): delivered to at least one traveler, or not
 * delivered to any; a traveler not served needs its cause; a no-show without notice is a service not delivered that stays charged. The
 * travelers reserved belong to commercial-reservation and are given to {@link #checkAgainst}, never kept here. It is a value.
 */
public record ServiceRecord(UUID reservationId, ServiceResult result, int travelersServed, String cause, boolean noShow) {

  private static final int CAUSE_MAX = 500;

  public ServiceRecord {
    if (reservationId == null || result == null) {
      throw new InvalidDataException("services", "Each service needs its reservation and its result");
    }
    boolean delivered = result == ServiceResult.DELIVERED;
    if (travelersServed < 0 || delivered != (travelersServed >= 1)) {
      throw new InvalidDataException("travelersServed", "A delivered service serves at least one traveler and a not delivered one serves none");
    }
    if (noShow && delivered) {
      throw new InvalidDataException("noShow", "A person who did not appear is a service not delivered");
    }
    if (cause != null && (cause.isBlank() || cause.length() > CAUSE_MAX)) {
      throw new InvalidDataException("cause", "The cause has 1 to " + CAUSE_MAX + " characters");
    }
  }

  /** Judges the record against the travelers the reservation holds: no more served than reserved, and a cause for every one not served. */
  public void checkAgainst(int travelersReserved) {
    if (travelersServed > travelersReserved) {
      throw new BusinessRuleViolationException(
          "The reservation " + reservationId + " has " + travelersReserved + " travelers and " + travelersServed + " were served");
    }
    if (travelersServed < travelersReserved && cause == null) {
      throw new InvalidDataException("cause", "Every traveler not served needs its cause");
    }
  }

  /** The real base: the travelers served of the services delivered (RN-EJE-003). */
  public static int realBase(Collection<ServiceRecord> records) {
    return records.stream().filter(record -> record.result() == ServiceResult.DELIVERED).mapToInt(ServiceRecord::travelersServed).sum();
  }
}
