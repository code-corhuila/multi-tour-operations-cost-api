package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import java.util.UUID;

/** The {@code Vehicle} of the contract: what a person needs to tell vehicles apart and count their seats. The entity is not serialized. */
record VehicleResponse(UUID vehicleId, String name, int capacity) {

  static VehicleResponse of(Vehicle vehicle) {
    return new VehicleResponse(vehicle.id(), vehicle.name(), vehicle.capacity());
  }
}
