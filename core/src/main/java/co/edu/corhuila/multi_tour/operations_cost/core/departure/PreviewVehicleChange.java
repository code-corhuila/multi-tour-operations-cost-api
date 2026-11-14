package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleCapacity;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleRules;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicles;
import java.util.List;
import java.util.UUID;

/**
 * What the capacity of a {@code SCHEDULED} departure is today and what a whole new set of vehicles would make it, so that the saga that
 * changes them knows the ceiling to ask for before it touches anything (HU-OPS-002 part B, api spec 008). It judges the set as the change
 * itself will (the state, the tour and the vehicles, with the departure's own vehicles not counting as an overlap) and writes nothing, so
 * the saga learns a refusal here. The tenant comes from the route: only the service role reaches it. The answer is advisory: the change
 * judges again in its own transaction, and {@code lockAll} outside one only reads (its locks end with the statement).
 */
public final class PreviewVehicleChange {

  private final DepartureRepository departures;
  private final Vehicles vehicles;
  private final Catalog catalog;

  public PreviewVehicleChange(DepartureRepository departures, Vehicles vehicles, Catalog catalog) {
    this.departures = departures;
    this.vehicles = vehicles;
    this.catalog = catalog;
  }

  public record Command(UUID tenantId, String departureCode, List<UUID> vehicleIds) {}

  public record Preview(UUID departureId, int currentEffectiveCapacity, int newEffectiveCapacity) {}

  public Preview execute(Command command) {
    List<UUID> vehicleIds = Requests.vehicleIds(command.vehicleIds(), 1);
    Departure departure = departures.findByCode(command.tenantId(), command.departureCode())
        .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    if (departure.status() != DepartureStatus.SCHEDULED) {
      throw new InvalidStatusTransitionException(departure.status(), "change its vehicles");
    }
    CatalogTour tour = catalog.tour(departure.serviceId())
        .orElseThrow(() -> new BusinessRuleViolationException("The tour is not in the catalog of the tenant"));
    if (!tour.transportRequired()) {
      throw new BusinessRuleViolationException("The tour does not require transport, so it takes no vehicles");
    }
    List<Vehicle> wanted = VehicleRules.requireKnown(vehicles, command.tenantId(), vehicleIds);
    VehicleRules.requireFree(vehicles, command.tenantId(), vehicleIds, departure.start(), departure.estimatedEnd(), departure.id());
    int today = VehicleCapacity.total(vehicles.ofDeparture(command.tenantId(), departure.id()));
    return new Preview(departure.id(), VehicleCapacity.effective(tour.capacity(), true, today),
        VehicleCapacity.effective(tour.capacity(), true, VehicleCapacity.total(wanted)));
  }
}
