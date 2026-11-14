package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Records the extraordinary cancellation of one reservation of a running departure (HU-OPS-005, api 009). The flow is the one of
 * {@link SaveServices}: a repeated key answers the departure it was registered for; the state is judged before another domain is asked
 * anything; the reservations are read before the transaction opens, so a failure there writes nothing; inside it the departure is locked,
 * judged again, and the cancellation, its event and the key are written together. The reservation itself is never written here: its owner
 * learns of the cancellation by the event and decides to reschedule or to refund. The route judges who may call.
 */
public final class RecordExtraordinaryCancellation {

  private final DepartureRepository departures;
  private final ExtraordinaryCancellations cancellations;
  private final Outbox outbox;
  private final IdempotencyKeys keys;
  private final ServicesReader reader;
  private final DepartureViews views;
  private final IdGenerator ids;
  private final Transaction transaction;
  private final Clock clock;

  public RecordExtraordinaryCancellation(
      DepartureRepository departures, ExtraordinaryCancellations cancellations, Outbox outbox, IdempotencyKeys keys, ServicesReader reader,
      DepartureViews views, IdGenerator ids, Transaction transaction, Clock clock) {
    this.departures = departures;
    this.cancellations = cancellations;
    this.outbox = outbox;
    this.keys = keys;
    this.reader = reader;
    this.views = views;
    this.ids = ids;
    this.transaction = transaction;
    this.clock = clock;
  }

  public record Command(Caller caller, String departureCode, String reservationId, String idempotencyKey, String justification, String correlationId) {}

  /** The departure as it is now, its reservations (absent if their owner could not be read), and whether this request recorded. */
  public record Recorded(DepartureView view, Optional<ServicesView> services, boolean recorded) {}

  public Recorded execute(Command command) {
    UUID tenantId = Requests.uuid(command.caller().tenantId());
    UUID account = Requests.uuid(command.caller().subject());
    Requests.requireKey(command.idempotencyKey());
    Requests.requireTrace(command.correlationId());
    Instant now = clock.instant();
    ExtraordinaryCancellation cancellation = new ExtraordinaryCancellation(reservation(command.reservationId()), command.justification(), account, now);
    Departure departure =
        departures.findByCode(tenantId, command.departureCode()).orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    Optional<UUID> previous = keys.departureOf(tenantId, IdempotencyKeys.RECORD_EXTRAORDINARY_CANCELLATION, command.idempotencyKey());
    if (previous.isPresent()) {
      return repeat(tenantId, departure, previous.get());
    }
    departure.acceptExtraordinaryCancellation();
    judgeAgainst(tenantId, departure, reader.reservationsOf(departure), cancellation.reservationId());
    DepartureReads reads = views.read(departure);
    try {
      transaction.run(() -> {
        Departure current =
            departures.findForUpdate(tenantId, departure.id()).orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
        current.acceptExtraordinaryCancellation();
        if (cancellations.exists(tenantId, current.id(), cancellation.reservationId())) {
          throw new BusinessRuleViolationException("The reservation " + cancellation.reservationId() + " was already cancelled");
        }
        cancellations.add(tenantId, current.id(), cancellation);
        outbox.add(CancellationEvents.recorded(ids.next(), current, cancellation, command.correlationId(), now));
        keys.register(tenantId, IdempotencyKeys.RECORD_EXTRAORDINARY_CANCELLATION, command.idempotencyKey(), current.id());
        return current;
      });
    } catch (InvalidStatusTransitionException | BusinessRuleViolationException | DuplicateKeyException lostTheRace) {
      // another request with the same key committed first (its cancellation is the "already cancelled" this one met) and this one rolled back: its departure is the answer
      Optional<UUID> winner = keys.departureOf(tenantId, IdempotencyKeys.RECORD_EXTRAORDINARY_CANCELLATION, command.idempotencyKey());
      if (winner.isEmpty()) {
        throw lostTheRace;
      }
      return repeat(tenantId, departure, winner.get());
    }
    Departure current = departures.find(tenantId, departure.id()).orElse(departure);
    return new Recorded(reads.view(current), reader.read(current), true);
  }

  private static UUID reservation(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      throw new InvalidDataException("reservationId", "The reservation id must be a UUID");
    }
  }

  private void judgeAgainst(UUID tenantId, Departure departure, List<ReservationOfDeparture> held, UUID reservationId) {
    ReservationOfDeparture reservation =
        held.stream().filter(r -> r.reservationId().equals(reservationId)).findFirst()
            .orElseThrow(() -> new BusinessRuleViolationException("The reservation " + reservationId + " is not of this departure"));
    if (reservation.state() != ReservationState.IN_EXECUTION) {
      throw new BusinessRuleViolationException("The reservation " + reservationId + " is not in execution");
    }
    if (cancellations.exists(tenantId, departure.id(), reservationId)) {
      throw new BusinessRuleViolationException("The reservation " + reservationId + " was already cancelled");
    }
  }

  /** A repeat answers the departure that the key was registered for, and only that one: a key is not good for another departure. */
  private Recorded repeat(UUID tenantId, Departure asked, UUID registeredFor) {
    if (!registeredFor.equals(asked.id())) {
      throw new BusinessRuleViolationException("The Idempotency-Key was already used for another departure");
    }
    Departure current = departures.find(tenantId, registeredFor).orElse(asked);
    return new Recorded(views.of(current), reader.read(current), false);
  }
}
