package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: the operational costs of the departures (HU-OPS-007). A line is never rewritten; its key of creation is part of it. */
public interface OperationalCosts {

  /** Adds a line with the key of its creation. Throws {@link DuplicateKeyException} when the tenant already used the key. */
  void add(OperationalCost cost, String idempotencyKey);

  Optional<OperationalCost> findByKey(UUID tenantId, String idempotencyKey);

  /** The lines of a departure in the order they were recorded. */
  List<OperationalCost> costsOf(UUID tenantId, UUID departureId);
}
