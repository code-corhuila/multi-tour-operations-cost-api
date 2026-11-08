package co.edu.corhuila.multi_tour.operations_cost.app;

import co.edu.corhuila.multi_tour.operations_cost.core.outbox.OutboxEvents;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.RetryPolicy;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.StoredEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** The outbox in memory, so the internal routes are tried on their own: the SQL has its own tests against the real engine. */
final class InMemoryOutboxEvents implements OutboxEvents {

  static final InMemoryOutboxEvents STORE = new InMemoryOutboxEvents();

  final List<StoredEvent> events = new ArrayList<>();

  @Override
  public synchronized Page<StoredEvent> due(Instant now, int page, int limit) {
    List<StoredEvent> due = events.stream()
        .filter(e -> e.status() == StoredEvent.Status.PENDING && !e.nextAttemptAt().isAfter(now))
        .sorted(Comparator.comparing(StoredEvent::occurredAt)).toList();
    int from = Math.min((page - 1) * limit, due.size());
    return new Page<>(due.subList(from, Math.min(from + limit, due.size())), page, limit, due.size());
  }

  @Override
  public synchronized Optional<StoredEvent> markPublished(UUID eventId, Instant at) {
    return replace(eventId, e -> e.published(at));
  }

  @Override
  public synchronized Optional<StoredEvent> recordFailedAttempt(UUID eventId, String error, Instant at, RetryPolicy policy) {
    return replace(eventId, e -> e.withFailedAttempt(error, at, policy));
  }

  private Optional<StoredEvent> replace(UUID id, UnaryOperator<StoredEvent> change) {
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i).eventId().equals(id)) {
        events.set(i, change.apply(events.get(i)));
        return Optional.of(events.get(i));
      }
    }
    return Optional.empty();
  }
}
