package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleCapacity;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleRules;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicles;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Schedules a departure of a tour of the catalog (HU-OPS-001, AC1, AC2, AC5, AC6), with the vehicles of a tour that requires
 * transport (HU-OPS-002). The reads of the other domain happen before the transaction opens, so no connection waits on another
 * service; the departure, its idempotency key, its vehicles and its events are written together. The event of the vehicles
 * follows that of the scheduling, a moment later, because commercial-reservation answers a vehicle event of a departure it does
 * not know yet with 400. The route judges the role, this judges that the caller is a person of a tenant.
 */
public final class ScheduleDeparture {

  private final DepartureRepository departures;
  private final Outbox outbox;
  private final IdempotencyKeys keys;
  private final Catalog catalog;
  private final Vehicles vehicles;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  public ScheduleDeparture(
      DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, Catalog catalog, Vehicles vehicles,
      Transaction transaction, IdGenerator ids, Clock clock) {
    this.departures = departures;
    this.outbox = outbox;
    this.keys = keys;
    this.catalog = catalog;
    this.vehicles = vehicles;
    this.transaction = transaction;
    this.ids = ids;
    this.clock = clock;
  }

  public record Command(
      Caller caller, String idempotencyKey, UUID serviceId, Instant start, Instant end, String timeZone,
      String correlationId, List<UUID> vehicleIds) {

    public Command(
        Caller caller, String idempotencyKey, UUID serviceId, Instant start, Instant end, String timeZone, String correlationId) {
      this(caller, idempotencyKey, serviceId, start, end, timeZone, correlationId, List.of());
    }
  }

  /**
   * The departure, the capacity of its tour, whether the tour requires transport, its vehicles, and whether this request created
   * it (false: the key had been used).
   */
  public record Scheduled(Departure departure, int capacity, boolean created, boolean transportRequired, List<Vehicle> vehicles) {}

  public Scheduled execute(Command command) {
    UUID tenantId = Requests.uuid(command.caller().tenantId());
    UUID account = Requests.uuid(command.caller().subject());
    String key = command.idempotencyKey();
    Requests.requireKey(key);
    Requests.requireTrace(command.correlationId());
    String trace = command.correlationId();
    var previous = alreadyCreated(tenantId, key);
    if (previous.isPresent()) {
      return answer(previous.get(), false);
    }
    if (command.serviceId() == null) {
      throw new InvalidDataException("serviceId", "The field serviceId is missing or not valid");
    }
    List<UUID> vehicleIds = Requests.vehicleIds(command.vehicleIds(), 0);
    Instant now = clock.instant();
    // everything the request itself can get wrong is judged before another domain is asked anything (plan D-05)
    Departure.schedule(new UUID(0, 0), tenantId, "SAL-000", command.serviceId(), command.start(), command.end(),
        command.timeZone(), account, now);
    CatalogTour tour = catalog.tour(command.serviceId())
        .filter(found -> found.type() == CatalogTour.Type.TOUR)
        .orElseThrow(() -> new BusinessRuleViolationException("The tour is not in the catalog of the tenant"));
    if (!tour.active()) {
      throw new BusinessRuleViolationException("The tour is not active in the catalog");
    }
    if (!vehicleIds.isEmpty() && !tour.transportRequired()) {
      throw new BusinessRuleViolationException("The tour does not require transport, so it takes no vehicles");
    }
    try {
      Created made = transaction.run(() -> {
        List<Vehicle> assigned = vehicleIds.isEmpty() ? List.of() : VehicleRules.requireKnown(vehicles, tenantId, vehicleIds);
        Departure created = Departure.schedule(ids.next(), tenantId, departures.nextCode(tenantId), tour.serviceId(),
            command.start(), command.end(), command.timeZone(), account, now);
        VehicleRules.requireFree(vehicles, tenantId, vehicleIds, created.start(), created.estimatedEnd(), created.id());
        departures.save(created);
        keys.register(tenantId, key, created.id());
        outbox.add(scheduledEvent(created, account, trace, now));
        if (!vehicleIds.isEmpty()) {
          vehicles.assign(tenantId, created.id(), vehicleIds, account, now);
          outbox.add(VehicleEvents.changed(ids.next(), created, VehicleCapacity.total(assigned), account, trace, now.plusMillis(1)));
        }
        return new Created(created, assigned);
      });
      return new Scheduled(made.departure(), tour.capacity(), true, tour.transportRequired(), made.vehicles());
    } catch (DuplicateKeyException lostTheRace) {
      // another request with the same key committed first and this one rolled back: the answer is that one's departure
      Departure winner = alreadyCreated(tenantId, key).orElseThrow(() -> lostTheRace);
      return answer(winner, false);
    }
  }

  private record Created(Departure departure, List<Vehicle> vehicles) {}

  private Optional<Departure> alreadyCreated(UUID tenantId, String key) {
    return keys.departureOf(tenantId, key).map(id -> departures.find(tenantId, id)
        .orElseThrow(() -> new IllegalStateException("An idempotency key points to a departure that is not there")));
  }

  /**
   * A repeat must answer 200, so it does not fail when the catalog is down: the capacity is not stored here, and when it
   * cannot be read (or the tour left the catalog) it is 0. Declared as gap G-API-7 of spec 002.
   */
  private Scheduled answer(Departure departure, boolean created) {
    Optional<CatalogTour> tour = tourOf(departure.serviceId());
    return new Scheduled(departure, tour.map(CatalogTour::capacity).orElse(0), created,
        tour.map(CatalogTour::transportRequired).orElse(false), vehicles.ofDeparture(departure.tenantId(), departure.id()));
  }

  private Optional<CatalogTour> tourOf(UUID serviceId) {
    try {
      return catalog.tour(serviceId);
    } catch (DependencyUnavailableException unavailable) {
      return Optional.empty();
    }
  }

  private OutboxEvent scheduledEvent(Departure departure, UUID account, String trace, Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", departure.tenantId().toString());
    payload.put("salidaId", departure.id().toString());
    payload.put("serviceId", departure.serviceId().toString());
    payload.put("startsAt", departure.start().toString());
    payload.put("estimatedEndsAt", departure.estimatedEnd().toString());
    return new OutboxEvent(ids.next(), "SalidaScheduled", departure.id(), departure.tenantId(), account, trace, payload, now);
  }
}
