package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Schedules a departure of a tour of the catalog (HU-OPS-001, AC1, AC2, AC5, AC6). The reads of the other domain happen
 * before the transaction opens, so no connection waits on another service; the departure, its idempotency key and its event
 * are written together. The route judges the role, this judges that the caller is a person of a tenant.
 */
public final class ScheduleDeparture {

  private static final int MIN_KEY = 8;
  private static final int MAX_KEY = 128;

  private final DepartureRepository departures;
  private final Outbox outbox;
  private final IdempotencyKeys keys;
  private final Catalog catalog;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  public ScheduleDeparture(
      DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, Catalog catalog, Transaction transaction,
      IdGenerator ids, Clock clock) {
    this.departures = departures;
    this.outbox = outbox;
    this.keys = keys;
    this.catalog = catalog;
    this.transaction = transaction;
    this.ids = ids;
    this.clock = clock;
  }

  public record Command(Caller caller, String idempotencyKey, UUID serviceId, Instant start, Instant end, String timeZone) {}

  /** The departure, the capacity of its tour and whether this request created it (false: the key had been used). */
  public record Scheduled(Departure departure, int capacity, boolean created) {}

  public Scheduled execute(Command command) {
    UUID tenantId = uuid(command.caller().tenantId());
    UUID account = uuid(command.caller().subject());
    String key = command.idempotencyKey();
    if (key == null || key.length() < MIN_KEY || key.length() > MAX_KEY) {
      throw new InvalidDataException("Idempotency-Key", "The Idempotency-Key must have 8 to 128 characters");
    }
    var previous = alreadyCreated(tenantId, key);
    if (previous.isPresent()) {
      return new Scheduled(previous.get(), capacityOf(previous.get().serviceId()), false);
    }
    if (command.serviceId() == null) {
      throw new InvalidDataException("serviceId", "The field serviceId is missing or not valid");
    }
    CatalogTour tour = catalog.tour(command.serviceId())
        .filter(found -> found.type() == CatalogTour.Type.TOUR)
        .orElseThrow(() -> new BusinessRuleViolationException("The tour is not in the catalog of the tenant"));
    if (!tour.active()) {
      throw new BusinessRuleViolationException("The tour is not active in the catalog");
    }
    Instant now = clock.instant();
    try {
      Departure departure = transaction.run(() -> {
        Departure created = Departure.schedule(ids.next(), tenantId, departures.nextCode(tenantId), tour.serviceId(),
            command.start(), command.end(), command.timeZone(), account, now);
        departures.save(created);
        keys.register(tenantId, key, created.id());
        outbox.add(scheduledEvent(created, now));
        return created;
      });
      return new Scheduled(departure, tour.capacity(), true);
    } catch (DuplicateKeyException lostTheRace) {
      // another request with the same key committed first and this one rolled back: the answer is that one's departure
      Departure winner = alreadyCreated(tenantId, key).orElseThrow(() -> lostTheRace);
      return new Scheduled(winner, capacityOf(winner.serviceId()), false);
    }
  }

  private java.util.Optional<Departure> alreadyCreated(UUID tenantId, String key) {
    return keys.departureOf(tenantId, key).map(id -> departures.find(tenantId, id)
        .orElseThrow(() -> new IllegalStateException("An idempotency key points to a departure that is not there")));
  }

  /**
   * A repeat must answer 200, so it does not fail when the catalog is down: the capacity is not stored here, and when it
   * cannot be read (or the tour left the catalog) it is 0. Declared as gap G-API-7 of spec 002.
   */
  private int capacityOf(UUID serviceId) {
    try {
      return catalog.tour(serviceId).map(CatalogTour::capacity).orElse(0);
    } catch (DependencyUnavailableException unavailable) {
      return 0;
    }
  }

  private OutboxEvent scheduledEvent(Departure departure, Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", departure.tenantId().toString());
    payload.put("salidaId", departure.id().toString());
    payload.put("serviceId", departure.serviceId().toString());
    payload.put("startsAt", departure.start().toString());
    payload.put("estimatedEndsAt", departure.estimatedEnd().toString());
    return new OutboxEvent(ids.next(), "SalidaScheduled", departure.id(), departure.tenantId(), payload, now);
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      throw new CallerNotAllowedException();
    }
  }
}
