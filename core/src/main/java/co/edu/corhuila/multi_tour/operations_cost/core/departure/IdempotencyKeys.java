package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.Optional;
import java.util.UUID;

/** Port: the keys of the requests already made, per tenant and per operation (a key is good for one operation only). */
public interface IdempotencyKeys {

  String SCHEDULE = "scheduleDeparture";
  String START = "registerStart";
  String FINISH = "finishDeparture";
  String REGULARIZE = "regularizeDeparture";
  String SAVE_SERVICES = "saveServices";
  String RECORD_EXTRAORDINARY_CANCELLATION = "recordExtraordinaryCancellation";

  Optional<UUID> departureOf(UUID tenantId, String operation, String key);

  void register(UUID tenantId, String operation, String key, UUID departureId);

  default Optional<UUID> departureOf(UUID tenantId, String key) {
    return departureOf(tenantId, SCHEDULE, key);
  }

  default void register(UUID tenantId, String key, UUID departureId) {
    register(tenantId, SCHEDULE, key, departureId);
  }
}
