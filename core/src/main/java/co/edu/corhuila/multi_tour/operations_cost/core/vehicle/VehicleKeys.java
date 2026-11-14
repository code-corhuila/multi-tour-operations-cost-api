package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import java.util.Optional;
import java.util.UUID;

/** Port: the keys of the vehicle creations already made, per tenant. */
public interface VehicleKeys {

  Optional<UUID> vehicleOf(UUID tenantId, String key);

  /** Throws {@link co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException} when another request registered it first. */
  void registerVehicle(UUID tenantId, String key, UUID vehicleId);
}
