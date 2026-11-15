package co.edu.corhuila.multi_tour.operations_cost.core.outbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port: the outbox as job 7 reads and updates it. Only this service changes an event: job 7 asks, the store decides. */
public interface OutboxEvents {

  /** The pending events whose next attempt is not after {@code now}, oldest first. */
  Page<StoredEvent> due(Instant now, int page, int limit);

  /** Published, whatever it was; empty when there is no such event. An event already published is returned as it is. */
  Optional<StoredEvent> markPublished(UUID eventId, Instant at);

  /** One more failed delivery of a pending event; one that is no longer pending is returned as it is. Empty when none. */
  Optional<StoredEvent> recordFailedAttempt(UUID eventId, String error, Instant at, RetryPolicy policy);
}
