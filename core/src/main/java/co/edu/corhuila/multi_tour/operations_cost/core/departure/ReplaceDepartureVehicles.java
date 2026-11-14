package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleCapacity;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleRules;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicles;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Puts the whole new set of vehicles on a {@code SCHEDULED} departure of a tour that requires transport: the vehicles that are not in it
 * come off and the new ones go on (HU-OPS-002 part B, api spec 008). It is what the saga asks after it lowered the ceiling of seats. The
 * state, the tour and the set are judged as the preview did, and again inside the transaction with the departure locked; the removal, the
 * assignment and the event are written together. A set equal to the current one changes nothing and writes nothing, so asking again
 * with the same key is safe. The saga, not a person, makes the change: the rows and the event name the system as author (as the mark
 * of job 3 does), and the tenant comes from the route.
 */
public final class ReplaceDepartureVehicles {

  /** The author of what the saga does: the assignment table needs one and the service token names no person. */
  private static final UUID SYSTEM = new UUID(0, 0);

  private final DepartureRepository departures;
  private final Vehicles vehicles;
  private final Outbox outbox;
  private final Catalog catalog;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  public ReplaceDepartureVehicles(
      DepartureRepository departures, Vehicles vehicles, Outbox outbox, Catalog catalog, Transaction transaction, IdGenerator ids,
      Clock clock) {
    this.departures = departures;
    this.vehicles = vehicles;
    this.outbox = outbox;
    this.catalog = catalog;
    this.transaction = transaction;
    this.ids = ids;
    this.clock = clock;
  }

  public record Command(UUID tenantId, String departureCode, List<UUID> vehicleIds, String idempotencyKey, String correlationId) {}

  /** The departure, its tour as the catalog says it, all its vehicles now, and whether this request changed them. */
  public record Replaced(Departure departure, CatalogTour tour, List<Vehicle> vehicles, boolean changed) {}

  public Replaced execute(Command command) {
    UUID tenantId = command.tenantId();
    Requests.requireKey(command.idempotencyKey());
    Requests.requireTrace(command.correlationId());
    List<UUID> vehicleIds = Requests.vehicleIds(command.vehicleIds(), 1);
    Departure departure = departures.findByCode(tenantId, command.departureCode())
        .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    if (departure.status() != DepartureStatus.SCHEDULED) {
      throw new InvalidStatusTransitionException(departure.status(), "change its vehicles");
    }
    CatalogTour tour = catalog.tour(departure.serviceId())
        .orElseThrow(() -> new BusinessRuleViolationException("The tour is not in the catalog of the tenant"));
    if (!tour.transportRequired()) {
      throw new BusinessRuleViolationException("The tour does not require transport, so it takes no vehicles");
    }
    Instant now = clock.instant();
    return transaction.run(() -> {
      Departure current = departures.findForUpdate(tenantId, departure.id())
          .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
      if (current.status() != DepartureStatus.SCHEDULED) {
        throw new InvalidStatusTransitionException(current.status(), "change its vehicles");
      }
      VehicleRules.requireKnown(vehicles, tenantId, vehicleIds);
      List<Vehicle> before = vehicles.ofDeparture(tenantId, current.id());
      List<UUID> beforeIds = before.stream().map(Vehicle::id).toList();
      List<UUID> added = vehicleIds.stream().filter(id -> !beforeIds.contains(id)).toList();
      List<UUID> removed = beforeIds.stream().filter(id -> !vehicleIds.contains(id)).toList();
      if (added.isEmpty() && removed.isEmpty()) {
        return new Replaced(current, tour, before, false);
      }
      VehicleRules.requireFree(vehicles, tenantId, added, current.start(), current.estimatedEnd(), current.id());
      vehicles.unassign(tenantId, current.id(), removed);
      vehicles.assign(tenantId, current.id(), added, SYSTEM, now);
      List<Vehicle> after = vehicles.ofDeparture(tenantId, current.id());
      outbox.add(VehicleEvents.changed(ids.next(), current, VehicleCapacity.total(after), null, command.correlationId(), now));
      return new Replaced(current, tour, after, true);
    });
  }
}
