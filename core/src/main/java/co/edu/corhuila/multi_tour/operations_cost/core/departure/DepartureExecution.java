package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.UUID;

/**
 * What happened in a departure: when it really started and ended, who recorded each, whether it ended as an interruption and
 * why (PDR K1), the mark of pending regularization (PDR section 16) and, when a person regularized it, who and when and why it
 * started earlier than scheduled (FINAL-006). The mark is not a state and does not close the departure; its moment stays after the
 * mark is cleared, as the anchor of the retention count (PDR Q1). It is a value: every change builds a new one.
 */
public record DepartureExecution(
    Instant actualStart, UUID startedBy, Instant actualEnd, UUID finishedBy, boolean interrupted, String interruptionCause,
    boolean pendingRegularization, Instant pendingMarkedAt, UUID regularizedBy, Instant regularizedAt, String earlyStartReason) {

  public static final DepartureExecution NONE = new DepartureExecution(null, null, null, null, false, null, false, null, null, null, null);

  DepartureExecution started(Instant at, UUID by) {
    return new DepartureExecution(at, by, null, null, false, null, false, pendingMarkedAt, regularizedBy, regularizedAt, earlyStartReason);
  }

  DepartureExecution finished(Instant at, UUID by, String cause) {
    return new DepartureExecution(actualStart, startedBy, at, by, cause != null, cause, false, pendingMarkedAt, regularizedBy, regularizedAt,
        earlyStartReason);
  }

  DepartureExecution marked(Instant at) {
    return new DepartureExecution(actualStart, startedBy, actualEnd, finishedBy, interrupted, interruptionCause, true, at, regularizedBy,
        regularizedAt, earlyStartReason);
  }

  DepartureExecution unmarked() {
    return new DepartureExecution(actualStart, startedBy, actualEnd, finishedBy, interrupted, interruptionCause, false, pendingMarkedAt,
        regularizedBy, regularizedAt, earlyStartReason);
  }

  /** A regularization records the whole execution at once, by one person, and the mark is no longer pending. */
  DepartureExecution regularized(Instant start, Instant end, String cause, String earlyReason, UUID by, Instant at) {
    return new DepartureExecution(start, by, end, by, cause != null, cause, false, pendingMarkedAt, by, at, earlyReason);
  }
}
