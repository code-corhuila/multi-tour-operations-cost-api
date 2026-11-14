package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Records what each reservation of a running departure received (HU-OPS-004, api 006). The flow is the one of the other recordings: a
 * repeated key answers the departure it was registered for; the state is judged before another domain is asked anything; the
 * reservations are read before the transaction opens, so a failure there writes nothing; inside it the departure is locked, judged again,
 * and the records and the key are written together. The route judges who may call.
 */
public final class SaveServices {

  private static final int MAX_ENTRIES = 100;

  private final DepartureRepository departures;
  private final ServiceRecords records;
  private final IdempotencyKeys keys;
  private final ServicesReader reader;
  private final DepartureViews views;
  private final Transaction transaction;
  private final Clock clock;

  public SaveServices(
      DepartureRepository departures, ServiceRecords records, IdempotencyKeys keys, ServicesReader reader, DepartureViews views,
      Transaction transaction, Clock clock) {
    this.departures = departures;
    this.records = records;
    this.keys = keys;
    this.reader = reader;
    this.views = views;
    this.transaction = transaction;
    this.clock = clock;
  }

  public record Command(Caller caller, String departureCode, String idempotencyKey, List<ServiceRecord> entries, String correlationId) {}

  /** The departure as it is now, the reservations with what they received (absent if their owner could not be read), and whether this request recorded. */
  public record Saved(DepartureView view, Optional<ServicesView> services, boolean recorded) {}

  public Saved execute(Command command) {
    UUID tenantId = Requests.uuid(command.caller().tenantId());
    UUID account = Requests.uuid(command.caller().subject());
    Requests.requireKey(command.idempotencyKey());
    Requests.requireTrace(command.correlationId());
    List<ServiceRecord> entries = judgeEntries(command.entries());
    Departure departure =
        departures.findByCode(tenantId, command.departureCode()).orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    Optional<UUID> previous = keys.departureOf(tenantId, IdempotencyKeys.SAVE_SERVICES, command.idempotencyKey());
    if (previous.isPresent()) {
      return repeat(tenantId, departure, previous.get());
    }
    departure.acceptServices();
    List<ReservationOfDeparture> held = reader.reservationsOf(departure);
    judgeAgainst(held, entries);
    DepartureReads reads = views.read(departure);
    Instant now = clock.instant();
    try {
      transaction.run(() -> {
        Departure current =
            departures.findForUpdate(tenantId, departure.id()).orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
        current.acceptServices();
        records.saveAll(tenantId, current.id(), entries, account, now);
        keys.register(tenantId, IdempotencyKeys.SAVE_SERVICES, command.idempotencyKey(), current.id());
        return current;
      });
    } catch (InvalidStatusTransitionException | DuplicateKeyException lostTheRace) {
      // another request with the same key committed first and this one rolled back: its departure is the answer
      Optional<UUID> winner = keys.departureOf(tenantId, IdempotencyKeys.SAVE_SERVICES, command.idempotencyKey());
      if (winner.isEmpty()) {
        throw lostTheRace;
      }
      return repeat(tenantId, departure, winner.get());
    }
    Departure current = departures.find(tenantId, departure.id()).orElse(departure);
    return new Saved(reads.view(current), Optional.of(reader.join(held, records.recordedFor(tenantId, current.id()))), true);
  }

  private List<ServiceRecord> judgeEntries(List<ServiceRecord> entries) {
    if (entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
      throw new InvalidDataException("services", "Send 1 to " + MAX_ENTRIES + " services");
    }
    Set<UUID> seen = new HashSet<>();
    for (ServiceRecord entry : entries) {
      if (!seen.add(entry.reservationId())) {
        throw new InvalidDataException("services", "The reservation " + entry.reservationId() + " is sent twice");
      }
    }
    return entries;
  }

  private void judgeAgainst(List<ReservationOfDeparture> held, List<ServiceRecord> entries) {
    Map<UUID, ReservationOfDeparture> byId = held.stream().collect(Collectors.toMap(ReservationOfDeparture::reservationId, Function.identity()));
    for (ServiceRecord entry : entries) {
      ReservationOfDeparture reservation = byId.get(entry.reservationId());
      if (reservation == null) {
        throw new BusinessRuleViolationException("The reservation " + entry.reservationId() + " is not of this departure");
      }
      if (reservation.state() != ReservationState.IN_EXECUTION) {
        throw new BusinessRuleViolationException("The reservation " + entry.reservationId() + " is not in execution");
      }
      entry.checkAgainst(reservation.travelers());
    }
  }

  /** A repeat answers the departure that the key was registered for, and only that one: a key is not good for another departure. */
  private Saved repeat(UUID tenantId, Departure asked, UUID registeredFor) {
    if (!registeredFor.equals(asked.id())) {
      throw new BusinessRuleViolationException("The Idempotency-Key was already used for another departure");
    }
    Departure current = departures.find(tenantId, registeredFor).orElse(asked);
    return new Saved(views.of(current), reader.read(current), false);
  }
}
