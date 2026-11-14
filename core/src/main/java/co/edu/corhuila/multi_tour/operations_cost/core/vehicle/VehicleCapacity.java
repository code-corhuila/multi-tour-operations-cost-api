package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import java.util.List;

/** The capacity of a departure that its vehicles decide (RN-SAL-001, RN-OPS-API-32). Computed, never stored: the row of capacity is commercial-reservation's. */
public final class VehicleCapacity {

  private VehicleCapacity() {}

  /** The seats of all the vehicles together. */
  public static int total(List<Vehicle> vehicles) {
    return vehicles.stream().mapToInt(Vehicle::capacity).sum();
  }

  /** The lower of the capacity of the tour and the total of the vehicles when the tour requires transport; the tour's otherwise. */
  public static int effective(int tourCapacity, boolean transportRequired, int vehicleTotal) {
    return transportRequired ? Math.min(tourCapacity, vehicleTotal) : tourCapacity;
  }
}
