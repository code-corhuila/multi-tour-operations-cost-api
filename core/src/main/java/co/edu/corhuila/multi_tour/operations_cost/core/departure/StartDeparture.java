package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Instant;

/**
 * Records the real start of a departure (HU-OPS-003, AC-API-04.4 to -04.6, -04.10) through the flow every recording shares
 * ({@link ExecutionRecorder}). Time never starts a departure: only this request does (RN-EJE-001). The route judges who may call.
 */
public final class StartDeparture {

  private final ExecutionRecorder recorder;

  public StartDeparture(
      DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, DepartureViews views, Transaction transaction, IdGenerator ids,
      Clock clock) {
    this.recorder = new ExecutionRecorder(departures, outbox, keys, views, transaction, ids, clock);
  }

  public record Command(Caller caller, String departureCode, String idempotencyKey, Instant startedAt, String correlationId) {}

  /** The departure as it is now, and whether this request recorded the start (false for a repeat). */
  public record Started(DepartureView view, boolean recorded) {}

  public Started execute(Command command) {
    var outcome = recorder.record(command.caller(), command.departureCode(), command.idempotencyKey(), command.correlationId(),
        new ExecutionRecorder.Spec(IdempotencyKeys.START, DepartureStatus.SCHEDULED, "be started",
            () -> {
              if (command.startedAt() == null) {
                throw new InvalidDataException("startedAt", "The field startedAt is missing or not valid");
              }
            },
            (current, account, now) -> current.start(command.startedAt(), account, now), ExecutionRecorder.Announce.one(ExecutionEvents::started)));
    return new Started(outcome.view(), outcome.recorded());
  }
}
