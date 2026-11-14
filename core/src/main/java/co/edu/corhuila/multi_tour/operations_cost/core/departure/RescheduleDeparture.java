package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Changes the start and the estimated end of a departure (HU-OPS-001, AC3 and AC4). What the request itself can get wrong, the
 * state of the departure and the clock are judged first, with nobody asked; the same values are no change and ask nothing; only
 * then are the seats held read from commercial-reservation, before the transaction opens, and the dates and the event are written
 * together. A departure with reservations is never changed (AC4): the way out is to cancel it and schedule a new one (HU-OPS-006).
 *
 * <p>Residual risk, declared: the seats are read from another service before the transaction, so a reservation made between that
 * reading and the update goes through. Only a check inside commercial-reservation (its own route, or its consumer of
 * {@code SalidaRescheduled}) can close that window; this service guarantees that the departure is still {@code SCHEDULED} with
 * the dates that were read when it writes, and that two changes never both win. The same values of a {@code SCHEDULED}
 * departure are no change even if its start has passed: nothing is written and nobody is asked.
 */
public final class RescheduleDeparture {

  private final DepartureRepository departures;
  private final Outbox outbox;
  private final Reservations reservations;
  private final Catalog catalog;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  public RescheduleDeparture(
      DepartureRepository departures, Outbox outbox, Reservations reservations, Catalog catalog, Transaction transaction,
      IdGenerator ids, Clock clock) {
    this.departures = departures;
    this.outbox = outbox;
    this.reservations = reservations;
    this.catalog = catalog;
    this.transaction = transaction;
    this.ids = ids;
    this.clock = clock;
  }

  public record Command(Caller caller, String departureCode, Instant start, Instant end, String correlationId) {}

  /** The departure as it is now, the capacity of its tour and whether this request changed it. */
  public record Rescheduled(Departure departure, int capacity, boolean changed) {}

  public Rescheduled execute(Command command) {
    UUID tenantId = uuid(command.caller().tenantId());
    UUID account = uuid(command.caller().subject());
    String trace = command.correlationId();
    if (trace == null || trace.isBlank() || trace.length() > 128) {
      throw new InvalidDataException("X-Correlation-Id", "The correlation id must have 1 to 128 characters");
    }
    Departure departure = departures.findByCode(tenantId, command.departureCode())
        .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    Instant now = clock.instant();
    Departure changed = departure.reschedule(command.start(), command.end(), now);
    if (changed.equals(departure)) {
      return new Rescheduled(departure, capacityOf(departure.serviceId()), false);
    }
    requireNoReservations(departure);
    transaction.run(() -> {
      if (!departures.updateSchedule(departure, changed)) {
        throw new BusinessRuleViolationException("The departure changed while the request was being decided; try again");
      }
      outbox.add(rescheduledEvent(changed, account, trace, now));
      return null;
    });
    return new Rescheduled(changed, capacityOf(changed.serviceId()), true);
  }

  /**
   * commercial-reservation has no route that counts the reservations of a departure, so "has reservations" is "holds seats"
   * (decision D2 of spec 002). With no row there, the departure is free only while its own event is still waiting to be
   * delivered (that service cannot have received it); otherwise it cannot be proved empty, and it is not changed.
   */
  private void requireNoReservations(Departure departure) {
    var seats = reservations.seatsOf(departure.serviceId(), departure.id());
    if (seats.isPresent()) {
      if (seats.get().held() > 0) {
        throw new BusinessRuleViolationException(
            "The departure has reservations; cancel it and schedule a new one instead of changing its date");
      }
      return;
    }
    if (!outbox.waiting(departure.tenantId(), departure.id(), "SalidaScheduled")) {
      throw new BusinessRuleViolationException("The reservations of the departure cannot be confirmed; try again later");
    }
  }

  /** The capacity is not stored here; when it cannot be read the answer carries 0 (gap G-API-7). */
  private int capacityOf(UUID serviceId) {
    try {
      return catalog.tour(serviceId).map(CatalogTour::capacity).orElse(0);
    } catch (DependencyUnavailableException unavailable) {
      return 0;
    }
  }

  private OutboxEvent rescheduledEvent(Departure departure, UUID account, String trace, Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", departure.tenantId().toString());
    payload.put("salidaId", departure.id().toString());
    payload.put("serviceId", departure.serviceId().toString());
    payload.put("startsAt", departure.start().toString());
    payload.put("estimatedEndsAt", departure.estimatedEnd().toString());
    return new OutboxEvent(ids.next(), "SalidaRescheduled", departure.id(), departure.tenantId(), account, trace, payload, now);
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      throw new CallerNotAllowedException();
    }
  }
}
