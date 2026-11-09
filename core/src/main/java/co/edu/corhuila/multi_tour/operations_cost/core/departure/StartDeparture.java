package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Records the real start of a departure (HU-OPS-003, AC-API-04.4 to -04.6, -04.10). A repeated key answers the departure already
 * started. What the other domains say is read before the transaction opens, so a failure writes nothing; inside it the departure is
 * locked, its state judged again, and the change, its event and the key are written together. Time never starts a departure:
 * only this request does (RN-EJE-001). The route judges who may call.
 */
public final class StartDeparture {

  private final DepartureRepository departures;
  private final Outbox outbox;
  private final IdempotencyKeys keys;
  private final DepartureViews views;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  public StartDeparture(
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

  public record Command(Caller caller, String departureCode, String idempotencyKey, Instant startedAt, String correlationId) {}

  /** The departure as it is now, and whether this request recorded the start (false for a repeat). */
  public record Started(DepartureView view, boolean recorded) {}

  public Started execute(Command command) {
    UUID tenantId = Requests.uuid(command.caller().tenantId());
    UUID account = Requests.uuid(command.caller().subject());
    Requests.requireKey(command.idempotencyKey());
    Requests.requireTrace(command.correlationId());
    if (command.startedAt() == null) {
      throw new InvalidDataException("startedAt", "The field startedAt is missing or not valid");
    }
    Departure departure = departures.findByCode(tenantId, command.departureCode())
        .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    var previous = keys.departureOf(tenantId, IdempotencyKeys.START, command.idempotencyKey());
    if (previous.isPresent()) {
      return new Started(views.of(departures.find(tenantId, previous.get()).orElse(departure)), false);
    }
    // the state is judged before another domain is asked anything
    if (departure.status() != DepartureStatus.SCHEDULED) {
      throw new InvalidStatusTransitionException(departure.status(), "be started");
    }
    DepartureReads reads = views.read(departure);
    Instant now = clock.instant();
    Departure started = transaction.run(() -> {
      Departure current = departures.findForUpdate(tenantId, departure.id())
          .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
      Departure changed = current.start(command.startedAt(), account, now);
      if (!departures.updateExecution(current, changed)) {
        throw new InvalidStatusTransitionException(current.status(), "be started");
      }
      keys.register(tenantId, IdempotencyKeys.START, command.idempotencyKey(), changed.id());
      outbox.add(ExecutionEvents.started(ids.next(), changed, account, command.correlationId(), now));
      return changed;
    });
    return new Started(reads.view(started), true);
  }
}
