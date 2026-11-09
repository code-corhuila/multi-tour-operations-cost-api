package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdGenerator;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Transaction;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** The ports of the vehicle as in-memory fakes, with a transaction that puts everything back when it fails. */
final class VehicleWorld implements Vehicles, VehicleKeys, Transaction, IdGenerator {

  final Map<UUID, Vehicle> rows = new LinkedHashMap<>();
  final Map<String, UUID> keys = new HashMap<>();
  boolean keysFail;
  private int ids;

  @Override
  public <T> T run(Supplier<T> work) {
    Map<UUID, Vehicle> rowsBefore = new LinkedHashMap<>(rows);
    Map<String, UUID> keysBefore = new HashMap<>(keys);
    try {
      return work.get();
    } catch (RuntimeException failed) {
      rows.clear();
      rows.putAll(rowsBefore);
      keys.clear();
      keys.putAll(keysBefore);
      throw failed;
    }
  }

  @Override
  public void save(Vehicle vehicle) {
    rows.put(vehicle.id(), vehicle);
  }

  @Override
  public boolean nameTaken(UUID tenantId, String name) {
    return rows.values().stream().anyMatch(v -> v.tenantId().equals(tenantId) && v.name().equalsIgnoreCase(name.strip()));
  }

  @Override
  public Page<Vehicle> list(UUID tenantId, int page, int limit) {
    List<Vehicle> all = rows.values().stream().filter(v -> v.tenantId().equals(tenantId))
        .sorted(Comparator.comparing(v -> v.name().toLowerCase())).toList();
    int from = Math.min((page - 1) * limit, all.size());
    return new Page<>(all.subList(from, Math.min(from + limit, all.size())), page, limit, all.size());
  }

  @Override
  public List<Vehicle> lockAll(UUID tenantId, Collection<UUID> vehicleIds) {
    return vehicleIds.stream().sorted().map(rows::get).filter(v -> v != null && v.tenantId().equals(tenantId)).toList();
  }

  @Override
  public List<Vehicle> ofDeparture(UUID tenantId, UUID departureId) {
    return List.of();
  }

  @Override
  public Optional<String> overlapping(UUID tenantId, Collection<UUID> vehicleIds, Instant start, Instant end, UUID except) {
    return Optional.empty();
  }

  @Override
  public void assign(UUID tenantId, UUID departureId, Collection<UUID> vehicleIds, UUID by, Instant at) {
    // not used by the use cases of this package
  }

  @Override
  public Optional<UUID> vehicleOf(UUID tenantId, String key) {
    return Optional.ofNullable(keys.get(tenantId + "/" + key));
  }

  @Override
  public void registerVehicle(UUID tenantId, String key, UUID vehicleId) {
    if (keysFail) {
      throw new IllegalStateException("the key table is down");
    }
    keys.put(tenantId + "/" + key, vehicleId);
  }

  @Override
  public UUID next() {
    return new UUID(0, ++ids);
  }
}
