package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The flow that every recording of what happened in a departure shares (HU-OPS-003, api 004 D-04): the start, the end and the
 * regularization, and whatever comes next. A repeated key answers the departure it was registered for, and only that one; the state is judged before another
 * domain is asked anything; what the other domains say is read before the transaction opens, so a failure there writes nothing; inside
 * it the departure is locked, the change is made on what is there now, and the change, its event and the key are written together. The
 * loser of a race with the same key answers what the winner recorded. Each recording says only what is its own: the operation of its
 * key, the state it needs, what else the departure must be, the change and the events (a regularization tells the start and then
 * the end).
 */
final class ExecutionRecorder {

  /** The new state of the departure, from the one that is locked. It throws what the domain refuses. */
  interface Change {
    Departure apply(Departure current, UUID account, Instant now);
  }

  /** One event that tells the other domains about the change. */
  interface Single {
    OutboxEvent event(UUID eventId, Departure changed, UUID account, String trace, Instant at);
  }

  /** The events that tell the other domains about the change, in the order they must be read. */
  interface Announce {
    List<OutboxEvent> events(IdGenerator ids, Departure changed, UUID account, String trace, Instant at);

    static Announce one(Single single) {
      return (ids, changed, account, trace, at) -> List.of(single.event(ids.next(), changed, account, trace, at));
    }
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

  /**
   * What each recording says of itself: the operation of its key, the state it needs and the action it names when the state is not
   * that, what else the departure must be to admit it (judged with the state, before another domain is asked anything), what only it
   * can judge of the request (called after the caller, the key and the trace and before anything is read), the change and the events.
   */
  record Spec(
      String operation, DepartureStatus needs, String action, Consumer<Departure> admits, Runnable judgeRequest, Change change,
      Announce announce) {

    Spec(String operation, DepartureStatus needs, String action, Runnable judgeRequest, Change change, Announce announce) {
      this(operation, needs, action, departure -> {}, judgeRequest, change, announce);
    }
  }

  Outcome record(Caller caller, String departureCode, String key, String trace, Spec spec) {
    String operation = spec.operation();
    DepartureStatus needs = spec.needs();
    String action = spec.action();
    UUID tenantId = Requests.uuid(caller.tenantId());
    UUID account = Requests.uuid(caller.subject());
    Requests.requireKey(key);
    Requests.requireTrace(trace);
    spec.judgeRequest().run();
    Departure departure = departures.findByCode(tenantId, departureCode).orElseThrow(() -> new DepartureNotFoundException(departureCode));
    var previous = keys.departureOf(tenantId, operation, key);
    if (previous.isPresent()) {
      return repeat(tenantId, departure, previous.get());
    }
    // the state is judged before another domain is asked anything
    if (departure.status() != needs) {
      throw new InvalidStatusTransitionException(departure.status(), action);
    }
    spec.admits().accept(departure);
    DepartureReads reads = views.read(departure);
    Instant now = clock.instant();
    Departure changed;
    try {
      changed = transaction.run(() -> {
        Departure current = departures.findForUpdate(tenantId, departure.id()).orElseThrow(() -> new DepartureNotFoundException(departureCode));
        Departure next = spec.change().apply(current, account, now);
        if (!departures.updateExecution(current, next)) {
          throw new InvalidStatusTransitionException(current.status(), action);
        }
        keys.register(tenantId, operation, key, next.id());
        spec.announce().events(ids, next, account, trace, now).forEach(outbox::add);
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
