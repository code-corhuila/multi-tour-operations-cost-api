package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.UUID;

/**
 * What happened in a departure: when it really started and ended, who recorded each, whether it ended as an interruption and
 * why (PDR K1), and the mark of pending regularization (PDR section 16): set when the estimated end passes with no closing, it is
 * not a state and it does not close the departure. The moment of the mark stays after the mark is cleared, as the anchor of the
 * retention count (PDR Q1). It is a value: starting, finishing or marking a departure builds a new one.
 */
public record DepartureExecution(
    Instant actualStart, UUID startedBy, Instant actualEnd, UUID finishedBy, boolean interrupted, String interruptionCause,
    boolean pendingRegularization, Instant pendingMarkedAt) {

  public static final DepartureExecution NONE = new DepartureExecution(null, null, null, null, false, null, false, null);

  DepartureExecution started(Instant at, UUID by) {
    return new DepartureExecution(at, by, null, null, false, null, false, pendingMarkedAt);
  }

  DepartureExecution finished(Instant at, UUID by, String cause) {
    return new DepartureExecution(actualStart, startedBy, at, by, cause != null, cause, false, pendingMarkedAt);
  }

  DepartureExecution unmarked() {
    return new DepartureExecution(actualStart, startedBy, actualEnd, finishedBy, interrupted, interruptionCause, false, pendingMarkedAt);
  }

  DepartureExecution marked(Instant at) {
    return new DepartureExecution(actualStart, startedBy, actualEnd, finishedBy, interrupted, interruptionCause, true, at);
  }
}
