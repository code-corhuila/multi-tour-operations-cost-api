package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleCapacity;
import java.util.List;

/**
 * A departure as it is answered: with its effective capacity (computed, never stored), the capacity of its tour and of its vehicles,
 * whether the tour requires transport, the seats held as commercial-reservation keeps them, and its vehicles.
 */
public record DepartureView(
    Departure departure, int capacity, int tourCapacity, int vehicleCapacity, boolean transportRequired, int booked, List<Vehicle> vehicles) {

  /** The only place that computes the effective capacity of a departure (RN-OPS-API-32); the routes only carry what it says. */
  public static DepartureView of(Departure departure, int tourCapacity, boolean transportRequired, int booked, List<Vehicle> vehicles) {
    int vehicleCapacity = VehicleCapacity.total(vehicles);
    return new DepartureView(departure, VehicleCapacity.effective(tourCapacity, transportRequired, vehicleCapacity), tourCapacity, vehicleCapacity,
        transportRequired, booked, vehicles);
  }
}
