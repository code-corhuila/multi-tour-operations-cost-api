package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleKeys;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The keys of the vehicle creations already made. As in {@link JdbcIdempotencyKeys}, the scope carries the tenant. */
public final class JdbcVehicleKeys implements VehicleKeys {

  private final JdbcClient jdbc;

  public JdbcVehicleKeys(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<UUID> vehicleOf(UUID tenantId, String key) {
    return jdbc.sql("SELECT vehicle_id FROM vehicle_idempotency_key WHERE scope = :scope AND idempotency_key = :key")
        .param("scope", scope(tenantId))
        .param("key", key)
        .query(UUID.class)
        .optional();
  }

  @Override
  public void registerVehicle(UUID tenantId, String key, UUID vehicleId) {
    try {
      jdbc.sql("INSERT INTO vehicle_idempotency_key (scope, idempotency_key, vehicle_id) VALUES (:scope, :key, :vehicle)")
          .param("scope", scope(tenantId))
          .param("key", key)
          .param("vehicle", vehicleId)
          .update();
    } catch (org.springframework.dao.DuplicateKeyException another) {
      throw new DuplicateKeyException();
    }
  }

  private static String scope(UUID tenantId) {
    return "createVehicle/" + tenantId;
  }
}
