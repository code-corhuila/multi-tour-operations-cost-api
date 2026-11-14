package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Regularizes a departure that nobody started and whose estimated end passed (HU-OPS-003 part B, AC-API-05.6 to -05.11): a person
 * records what happened with the real hours, through the flow every recording shares ({@link ExecutionRecorder}). It finishes the
 * departure, so it tells the start and then the end, and commercial-reservation moves its reservations as with a normal recording
 * (PDR section 16). Time never regularizes a departure: only a person does (RN-EJE-001).
 */
public final class RegularizeDeparture {

  /** The second event is dated a moment later so that a reader sees the start before the end. */
  private static final Duration EVENT_ORDER_GAP = Duration.ofMillis(1);

  /** What happened. A departure that was not carried out is cancelled (HU-OPS-006), with the written reason of the person. */
  public enum Result { EXECUTED, INTERRUPTED, NOT_CARRIED_OUT }

  private final ExecutionRecorder recorder;

  public RegularizeDeparture(
      DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, DepartureViews views, Transaction transaction, IdGenerator ids,
      Clock clock) {
    this.recorder = new ExecutionRecorder(departures, outbox, keys, views, transaction, ids, clock);
  }

  /**
   * {@code interruption} is the cause of an interruption and is present only for {@code INTERRUPTED}; {@code notCarriedOutCause} is the
   * written reason of {@code NOT_CARRIED_OUT} and of no other result.
   */
  public record Command(
      Caller caller, String departureCode, String idempotencyKey, Result result, Instant actualStart, Instant actualEnd,
      String earlyStartReason, String interruption, String notCarriedOutCause, String correlationId) {

    public Command(
        Caller caller, String departureCode, String idempotencyKey, Result result, Instant actualStart, Instant actualEnd,
        String earlyStartReason, String interruption, String correlationId) {
      this(caller, departureCode, idempotencyKey, result, actualStart, actualEnd, earlyStartReason, interruption, null, correlationId);
    }
  }

  /** The departure as it is now, and whether this request recorded the regularization (false for a repeat). */
  public record Regularized(DepartureView view, boolean recorded) {}

  public Regularized execute(Command command) {
    var outcome = recorder.record(command.caller(), command.departureCode(), command.idempotencyKey(), command.correlationId(),
        new ExecutionRecorder.Spec(IdempotencyKeys.REGULARIZE, DepartureStatus.SCHEDULED, "be regularized",
            departure -> {
              if (!departure.execution().pendingRegularization()) {
                throw new InvalidStatusTransitionException(departure.status(), "be regularized");
              }
            },
            () -> judge(command),
            (current, account, now) -> command.result() == Result.NOT_CARRIED_OUT
                ? current.cancel(Cancellation.Cause.NOT_CARRIED_OUT, command.notCarriedOutCause(), account, now)
                : current.regularize(command.actualStart(), command.actualEnd(), command.interruption(), command.earlyStartReason(), account, now),
            (ids, changed, account, trace, at) -> command.result() == Result.NOT_CARRIED_OUT
                ? List.of(DepartureCancellationEvents.cancelled(ids.next(), changed, trace, at))
                : List.of(
                    ExecutionEvents.started(ids.next(), changed, account, trace, at),
                    ExecutionEvents.finalized(ids.next(), changed, account, trace, at.plus(EVENT_ORDER_GAP)))));
    return new Regularized(outcome.view(), outcome.recorded());
  }

  private static void judge(Command command) {
    if (command.result() == null) {
      throw new InvalidDataException("result", "The field result is missing or not valid");
    }
    if ((command.result() == Result.NOT_CARRIED_OUT) != (command.notCarriedOutCause() != null)) {
      throw new InvalidDataException("notCarriedOutCause", "A departure that was not carried out needs its reason and no other result takes one");
    }
    if (command.result() == Result.NOT_CARRIED_OUT) {
      // the rule of the reason lives in Cancellation: it is judged here, with the rest of the shape, before the departure is looked at
      new Cancellation(Instant.EPOCH, null, Cancellation.Cause.NOT_CARRIED_OUT, command.notCarriedOutCause());
    }
    if (command.result() == Result.NOT_CARRIED_OUT && (command.actualStart() != null || command.actualEnd() != null || command.earlyStartReason() != null)) {
      throw new InvalidDataException("result", "A departure that was not carried out has no real hours and no early start reason");
    }
    if ((command.result() == Result.INTERRUPTED) != (command.interruption() != null)) {
      throw new InvalidDataException("interruption", "An interruption needs its cause and a departure that was executed takes none");
    }
  }
}
