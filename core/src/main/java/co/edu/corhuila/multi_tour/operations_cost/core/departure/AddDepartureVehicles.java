package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleCapacity;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleRules;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicles;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adds vehicles to a {@code SCHEDULED} departure of a tour that requires transport (HU-OPS-002, AC2, AC3, AC5). Part A only adds,
 * so it never takes seats away and needs no saga; removing or replacing a vehicle is part B. The departure is found for the tenant
 * (another tenant's is "not found"), its state is judged before anything else is asked, the catalog is read before the transaction
 * opens, and the assignment and the event are written together. A vehicle already on the departure changes nothing and writes
 * nothing. The route judges the role.
 */
public final class AddDepartureVehicles {

  private final DepartureRepository departures;
  private final Vehicles vehicles;
  private final Outbox outbox;
  private final Catalog catalog;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  public AddDepartureVehicles(
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

  public record Command(Caller caller, String departureCode, List<UUID> vehicleIds, String correlationId) {}

  /** The departure, its tour as the catalog says it, all its vehicles now, and whether this request changed them. */
  public record Added(Departure departure, CatalogTour tour, List<Vehicle> vehicles, boolean changed) {}

  public Added execute(Command command) {
    UUID tenantId = Requests.uuid(command.caller().tenantId());
    UUID account = Requests.uuid(command.caller().subject());
    Requests.requireTrace(command.correlationId());
    List<UUID> vehicleIds = Requests.vehicleIds(command.vehicleIds(), 1);
    Departure departure = departures.findByCode(tenantId, command.departureCode())
        .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    VehicleChangeRules.requireScheduled(departure, "receive vehicles");
    CatalogTour tour = VehicleChangeRules.requireTransportTour(catalog, departure);
    Instant now = clock.instant();
    return transaction.run(() -> {
      // the departure was read before the transaction: lock it and decide on what it is now, then lock the vehicles
      Departure current = departures.findForUpdate(tenantId, departure.id())
          .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
      VehicleChangeRules.requireScheduled(current, "receive vehicles");
      VehicleRules.requireKnown(vehicles, tenantId, vehicleIds);
      List<Vehicle> before = vehicles.ofDeparture(tenantId, departure.id());
      List<UUID> beforeIds = before.stream().map(Vehicle::id).toList();
      List<UUID> added = vehicleIds.stream().filter(id -> !beforeIds.contains(id)).toList();
      if (added.isEmpty()) {
        return new Added(current, tour, before, false);
      }
      VehicleRules.requireFree(vehicles, tenantId, added, current.start(), current.estimatedEnd(), current.id());
      vehicles.assign(tenantId, current.id(), added, account, now);
      List<Vehicle> after = vehicles.ofDeparture(tenantId, current.id());
      outbox.add(VehicleEvents.changed(ids.next(), current, VehicleCapacity.total(after), account, command.correlationId(), now));
      return new Added(current, tour, after, true);
    });
  }
}
