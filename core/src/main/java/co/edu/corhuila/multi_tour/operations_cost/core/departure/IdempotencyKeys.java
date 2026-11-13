package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.Optional;
import java.util.UUID;

/** Port: the keys of the creations already made, per tenant. */
public interface IdempotencyKeys {

  Optional<UUID> departureOf(UUID tenantId, String key);

  void register(UUID tenantId, String key, UUID departureId);
}
