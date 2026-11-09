package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Records the end of a departure, or its interruption when a cause is given (HU-OPS-003, AC-API-04.7 to -04.10). An interruption still
 * ends the departure finished, never cancelled (PDR K1). Same shape as {@link StartDeparture}: a repeated key answers the departure
 * already finished, the other domains are read before the transaction, and the change, its event and the key are written together.
 * Time never finishes a departure: only this request does (RN-EJE-001).
 */
public final class FinishDeparture {

  private final DepartureRepository departures;
  private final Outbox outbox;
  private final IdempotencyKeys keys;
  private final DepartureViews views;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  public FinishDeparture(
      DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, DepartureViews views, Transaction transaction, IdGenerator ids,
      Clock clock) {
    this.departures = departures;
    this.outbox = outbox;
    this.keys = keys;
    this.views = views;
    this.transaction = transaction;
    this.ids = ids;
    this.clock = clock;
  }

  /** {@code interruptionCause} is null for a normal end and the cause of an interruption otherwise. */
  public record Command(Caller caller, String departureCode, String idempotencyKey, Instant finishedAt, String interruptionCause, String correlationId) {}

  /** The departure as it is now, and whether this request recorded the end (false for a repeat). */
  public record Finished(DepartureView view, boolean recorded) {}

  public Finished execute(Command command) {
    UUID tenantId = Requests.uuid(command.caller().tenantId());
    UUID account = Requests.uuid(command.caller().subject());
    Requests.requireKey(command.idempotencyKey());
    Requests.requireTrace(command.correlationId());
    if (command.finishedAt() == null) {
      throw new InvalidDataException("finishedAt", "The field finishedAt is missing or not valid");
    }
    Departure departure = departures.findByCode(tenantId, command.departureCode())
        .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    var previous = keys.departureOf(tenantId, IdempotencyKeys.FINISH, command.idempotencyKey());
    if (previous.isPresent()) {
      return repeat(tenantId, departure, previous.get());
    }
    // the state is judged before another domain is asked anything
    if (departure.status() != DepartureStatus.IN_EXECUTION) {
      throw new InvalidStatusTransitionException(departure.status(), "be finished");
    }
    DepartureReads reads = views.read(departure);
    Instant now = clock.instant();
    Departure finished;
    try {
      finished = transaction.run(() -> {
        Departure current = departures.findForUpdate(tenantId, departure.id())
            .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
        Departure changed = current.finish(command.finishedAt(), account, now, command.interruptionCause());
        if (!departures.updateExecution(current, changed)) {
          throw new InvalidStatusTransitionException(current.status(), "be finished");
        }
        keys.register(tenantId, IdempotencyKeys.FINISH, command.idempotencyKey(), changed.id());
        outbox.add(ExecutionEvents.finalized(ids.next(), changed, account, command.correlationId(), now));
        return changed;
      });
    } catch (InvalidStatusTransitionException | DuplicateKeyException lostTheRace) {
      // another request with the same key committed first and this one rolled back: its departure is the answer
      var winner = keys.departureOf(tenantId, IdempotencyKeys.FINISH, command.idempotencyKey());
      if (winner.isEmpty()) {
        throw lostTheRace;
      }
      return repeat(tenantId, departure, winner.get());
    }
    return new Finished(reads.view(finished), true);
  }

  /** A repeat answers the departure that the key was registered for, and only that one: a key is not good for another departure. */
  private Finished repeat(UUID tenantId, Departure asked, UUID registeredFor) {
    if (!registeredFor.equals(asked.id())) {
      throw new BusinessRuleViolationException("The Idempotency-Key was already used for another departure");
    }
    return new Finished(views.of(departures.find(tenantId, registeredFor).orElse(asked)), false);
  }
}
