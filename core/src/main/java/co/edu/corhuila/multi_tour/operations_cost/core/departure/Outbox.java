package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.UUID;

/** Port: writes an event in the same transaction as the change that causes it. */
public interface Outbox {

  void add(OutboxEvent event);

  /** Whether an event of that type of that aggregate of that tenant is still waiting to be delivered. */
  boolean waiting(UUID tenantId, UUID aggregateId, String eventType);
}
