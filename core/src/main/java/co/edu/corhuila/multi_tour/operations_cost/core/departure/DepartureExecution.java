package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.UUID;

/**
 * What happened in a departure: when it really started and ended, who recorded each, and whether it ended as an interruption
 * and why (PDR K1). Everything is empty while the departure is SCHEDULED. It is a value: starting or finishing a departure
 * builds a new one.
 */
public record DepartureExecution(
    Instant actualStart, UUID startedBy, Instant actualEnd, UUID finishedBy, boolean interrupted, String interruptionCause) {

  public static final DepartureExecution NONE = new DepartureExecution(null, null, null, null, false, null);

  DepartureExecution started(Instant at, UUID by) {
    return new DepartureExecution(at, by, null, null, false, null);
  }

  DepartureExecution finished(Instant at, UUID by, String cause) {
    return new DepartureExecution(actualStart, startedBy, at, by, cause != null, cause);
  }
}
