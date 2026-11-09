package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The flow that every recording of what happened in a departure shares (HU-OPS-003, api 004 D-04): the start and the end today, and
 * whatever comes next. A repeated key answers the departure it was registered for, and only that one; the state is judged before another
 * domain is asked anything; what the other domains say is read before the transaction opens, so a failure there writes nothing; inside
 * it the departure is locked, the change is made on what is there now, and the change, its event and the key are written together. The
 * loser of a race with the same key answers what the winner recorded. Each recording says only what is its own: the operation of its
 * key, the state it needs, the change and the event.
 */
final class ExecutionRecorder {

  /** The new state of the departure, from the one that is locked. It throws what the domain refuses. */
  interface Change {
    Departure apply(Departure current, UUID account, Instant now);
  }

  /** The event that tells the other domains about the change. */
  interface Announce {
    OutboxEvent event(UUID eventId, Departure changed, UUID account, String trace, Instant at);
  }

  /** What a recording answers: the departure as it is now, and whether this request recorded it (false for a repeat). */
  record Outcome(DepartureView view, boolean recorded) {}

  private final DepartureRepository departures;
  private final Outbox outbox;
  private final IdempotencyKeys keys;
  private final DepartureViews views;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  ExecutionRecorder(
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

  Outcome record(
      Caller caller, String departureCode, String key, String trace, String operation, DepartureStatus needs, String action, Change change,
      Announce announce) {
    UUID tenantId = Requests.uuid(caller.tenantId());
    UUID account = Requests.uuid(caller.subject());
    Requests.requireKey(key);
    Requests.requireTrace(trace);
    Departure departure = departures.findByCode(tenantId, departureCode).orElseThrow(() -> new DepartureNotFoundException(departureCode));
    var previous = keys.departureOf(tenantId, operation, key);
    if (previous.isPresent()) {
      return repeat(tenantId, departure, previous.get());
    }
    // the state is judged before another domain is asked anything
    if (departure.status() != needs) {
      throw new InvalidStatusTransitionException(departure.status(), action);
    }
    DepartureReads reads = views.read(departure);
    Instant now = clock.instant();
    Departure changed;
    try {
      changed = transaction.run(() -> {
        Departure current = departures.findForUpdate(tenantId, departure.id()).orElseThrow(() -> new DepartureNotFoundException(departureCode));
        Departure next = change.apply(current, account, now);
        if (!departures.updateExecution(current, next)) {
          throw new InvalidStatusTransitionException(current.status(), action);
        }
        keys.register(tenantId, operation, key, next.id());
        outbox.add(announce.event(ids.next(), next, account, trace, now));
        return next;
      });
    } catch (InvalidStatusTransitionException | DuplicateKeyException lostTheRace) {
      // another request with the same key committed first and this one rolled back: its departure is the answer
      var winner = keys.departureOf(tenantId, operation, key);
      if (winner.isEmpty()) {
        throw lostTheRace;
      }
      return repeat(tenantId, departure, winner.get());
    }
    return new Outcome(reads.view(changed), true);
  }

  /** A repeat answers the departure that the key was registered for, and only that one: a key is not good for another departure. */
  private Outcome repeat(UUID tenantId, Departure asked, UUID registeredFor) {
    if (!registeredFor.equals(asked.id())) {
      throw new BusinessRuleViolationException("The Idempotency-Key was already used for another departure");
    }
    return new Outcome(views.of(departures.find(tenantId, registeredFor).orElse(asked)), false);
  }
}
