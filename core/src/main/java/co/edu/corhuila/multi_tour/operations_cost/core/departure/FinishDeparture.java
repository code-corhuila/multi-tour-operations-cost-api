package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Instant;

/**
 * Records the end of a departure, or its interruption when a cause is given (HU-OPS-003, AC-API-04.7 to -04.10), through the flow
 * every recording shares ({@link ExecutionRecorder}). An interruption still ends the departure finished, never cancelled (PDR K1). Time
 * never finishes a departure: only this request does (RN-EJE-001).
 */
public final class FinishDeparture {

  private final ExecutionRecorder recorder;

  public FinishDeparture(
      DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, DepartureViews views, Transaction transaction, IdGenerator ids,
      Clock clock) {
    this.recorder = new ExecutionRecorder(departures, outbox, keys, views, transaction, ids, clock);
  }

  /** {@code interruptionCause} is null for a normal end and the cause of an interruption otherwise. */
  public record Command(Caller caller, String departureCode, String idempotencyKey, Instant finishedAt, String interruptionCause, String correlationId) {}

  /** The departure as it is now, and whether this request recorded the end (false for a repeat). */
  public record Finished(DepartureView view, boolean recorded) {}

  public Finished execute(Command command) {
    if (command.finishedAt() == null) {
      throw new InvalidDataException("finishedAt", "The field finishedAt is missing or not valid");
    }
    var outcome = recorder.record(command.caller(), command.departureCode(), command.idempotencyKey(), command.correlationId(),
        IdempotencyKeys.FINISH, DepartureStatus.IN_EXECUTION, "be finished",
        (current, account, now) -> current.finish(command.finishedAt(), account, now, command.interruptionCause()), ExecutionEvents::finalized);
    return new Finished(outcome.view(), outcome.recorded());
  }
}
