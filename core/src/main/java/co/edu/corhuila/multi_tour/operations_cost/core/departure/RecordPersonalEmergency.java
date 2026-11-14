package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Records the personal emergency of one reservation of a running departure (HU-OPS-008, api 011). The flow is the one of
 * {@link RecordExtraordinaryCancellation}: the shape of the request is judged first; a repeated key answers the departure it was registered
 * for; the state is judged before another domain is asked anything; the reservations are read before the transaction opens, so a failure
 * there writes nothing; inside it the departure is locked, judged again, and the emergency, its event and the key are written together.
 * The reservation itself is never written and no amount is calculated: the event tells its owner how many travelers cannot continue and the
 * owner decides the refund. The route judges who may call.
 */
public final class RecordPersonalEmergency {

  /** The only type of {@code registerNovelty} that this story offers; the ordinary novelty is another story. */
  public static final String PERSONAL_EMERGENCY = "PERSONAL_EMERGENCY";

  private static final String NOVELTY = "NOVELTY";

  private final DepartureRepository departures;
  private final PersonalEmergencies emergencies;
  private final Outbox outbox;
  private final IdempotencyKeys keys;
  private final Reservations reservations;
  private final DepartureViews views;
  private final IdGenerator ids;
  private final Transaction transaction;
  private final Clock clock;

  public RecordPersonalEmergency(
      DepartureRepository departures, PersonalEmergencies emergencies, Outbox outbox, IdempotencyKeys keys, Reservations reservations,
      DepartureViews views, IdGenerator ids, Transaction transaction, Clock clock) {
    this.departures = departures;
    this.emergencies = emergencies;
    this.outbox = outbox;
    this.keys = keys;
    this.reservations = reservations;
    this.views = views;
    this.ids = ids;
    this.transaction = transaction;
    this.clock = clock;
  }

  public record Command(
      Caller caller, String departureCode, String reservationId, String type, String idempotencyKey, String detail, Integer affected,
      String correlationId) {}

  /** The departure as it is now, and whether this request recorded the emergency (false for a repeat). */
  public record Recorded(DepartureView view, boolean recorded) {}

  public Recorded execute(Command command) {
    UUID tenantId = Requests.uuid(command.caller().tenantId());
    UUID account = Requests.uuid(command.caller().subject());
    Requests.requireKey(command.idempotencyKey());
    Requests.requireTrace(command.correlationId());
    requireOffered(command.type());
    if (command.affected() == null) {
      throw new InvalidDataException("affected", "The travelers affected are required");
    }
    Instant now = clock.instant();
    PersonalEmergency emergency = new PersonalEmergency(reservation(command.reservationId()), command.affected(), command.detail(), account, now);
    Departure departure =
        departures.findByCode(tenantId, command.departureCode()).orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    Optional<UUID> previous = keys.departureOf(tenantId, IdempotencyKeys.RECORD_PERSONAL_EMERGENCY, command.idempotencyKey());
    if (previous.isPresent()) {
      return repeat(tenantId, departure, previous.get());
    }
    departure.acceptPersonalEmergency();
    judgeAgainst(tenantId, departure, reservations.reservationsOf(departure.serviceId(), departure.id()), emergency);
    DepartureReads reads = views.read(departure);
    try {
      transaction.run(() -> {
        Departure current =
            departures.findForUpdate(tenantId, departure.id()).orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
        current.acceptPersonalEmergency();
        if (emergencies.hasEmergency(tenantId, current.id(), emergency.reservationId())) {
          throw new BusinessRuleViolationException("The reservation " + emergency.reservationId() + " already has a personal emergency");
        }
        emergencies.add(tenantId, current.id(), emergency);
        outbox.add(PersonalEmergencyEvents.recorded(ids.next(), current, emergency, command.correlationId(), now));
        keys.register(tenantId, IdempotencyKeys.RECORD_PERSONAL_EMERGENCY, command.idempotencyKey(), current.id());
        return current;
      });
    } catch (InvalidStatusTransitionException | BusinessRuleViolationException | DuplicateKeyException lostTheRace) {
      // another request with the same key committed first (its emergency is the "already has one" this one met) and this one rolled back: its departure is the answer
      Optional<UUID> winner = keys.departureOf(tenantId, IdempotencyKeys.RECORD_PERSONAL_EMERGENCY, command.idempotencyKey());
      if (winner.isEmpty()) {
        throw lostTheRace;
      }
      return repeat(tenantId, departure, winner.get());
    }
    Departure current = departures.find(tenantId, departure.id()).orElse(departure);
    return new Recorded(reads.view(current), true);
  }

  private static void requireOffered(String type) {
    if (NOVELTY.equals(type)) {
      throw new BusinessRuleViolationException("The ordinary novelty is not offered yet");
    }
    if (!PERSONAL_EMERGENCY.equals(type)) {
      throw new InvalidDataException("type", "The type is PERSONAL_EMERGENCY or NOVELTY");
    }
  }

  private static UUID reservation(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      throw new InvalidDataException("reservationId", "The reservation id must be a UUID");
    }
  }

  private void judgeAgainst(UUID tenantId, Departure departure, List<ReservationOfDeparture> held, PersonalEmergency emergency) {
    UUID reservationId = emergency.reservationId();
    ReservationOfDeparture reservation =
        held.stream().filter(r -> r.reservationId().equals(reservationId)).findFirst()
            .orElseThrow(() -> new BusinessRuleViolationException("The reservation " + reservationId + " is not of this departure"));
    if (reservation.state() != ReservationState.IN_EXECUTION) {
      throw new BusinessRuleViolationException("The reservation " + reservationId + " is not in execution");
    }
    if (emergency.affected() > reservation.travelers()) {
      throw new BusinessRuleViolationException("The travelers affected exceed the " + reservation.travelers() + " of the reservation");
    }
    if (emergencies.hasEmergency(tenantId, departure.id(), reservationId)) {
      throw new BusinessRuleViolationException("The reservation " + reservationId + " already has a personal emergency");
    }
  }

  /** A repeat answers the departure that the key was registered for, and only that one: a key is not good for another departure. */
  private Recorded repeat(UUID tenantId, Departure asked, UUID registeredFor) {
    if (!registeredFor.equals(asked.id())) {
      throw new BusinessRuleViolationException("The Idempotency-Key was already used for another departure");
    }
    Departure current = departures.find(tenantId, registeredFor).orElse(asked);
    return new Recorded(views.of(current), false);
  }
}
