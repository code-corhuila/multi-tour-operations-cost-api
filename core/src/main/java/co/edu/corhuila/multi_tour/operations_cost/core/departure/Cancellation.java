package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The cancellation of a departure (HU-OPS-006, api spec 010 RN-OPS-API-82): when, by whom when a person did, why and, for a departure
 * that did not take place, the written reason. A departure is cancelled once and for good (CA-029), so the instant is the one every later
 * request answers. The account lives in another domain and is kept as its id only; it is empty when the system cancelled. It is a value.
 */
public record Cancellation(Instant at, UUID by, Cause cause, String reason) {

  /** Not attributable to the customer is the saga's cause (RN-EJE-002); not performed is the regularization's, with its reason. */
  public enum Cause {
    NOT_ATTRIBUTABLE_TO_CUSTOMER,
    NOT_CARRIED_OUT
  }

  private static final int REASON_MAX = 500;

  public Cancellation {
    // the database keeps microseconds: what is answered the first time is what is read back later
    at = at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
    if (at == null || cause == null) {
      throw new InvalidDataException("cancellation", "A cancellation needs its instant and its cause");
    }
    boolean notPerformed = cause == Cause.NOT_CARRIED_OUT;
    if (notPerformed != (reason != null)) {
      throw new InvalidDataException("notCarriedOutCause", "Only a departure that was not carried out has a reason, and it needs one");
    }
    if (reason != null && (reason.isBlank() || reason.length() > REASON_MAX)) {
      throw new InvalidDataException("notCarriedOutCause", "The reason has 1 to " + REASON_MAX + " characters");
    }
  }
}
