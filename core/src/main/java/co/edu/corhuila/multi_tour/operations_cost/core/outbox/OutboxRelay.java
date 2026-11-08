package co.edu.corhuila.multi_tour.operations_cost.core.outbox;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import java.time.Clock;
import java.util.UUID;

/** What job 7 (the event relay) asks of this service's outbox (ADR-004 D4): read what waits, mark it delivered, report a failure. */
public final class OutboxRelay {

  private final OutboxEvents events;
  private final Clock clock;
  private final RetryPolicy policy;

  public OutboxRelay(OutboxEvents events, Clock clock, RetryPolicy policy) {
    this.events = events;
    this.clock = clock;
    this.policy = policy;
  }

  /** {@code page} from 1 and {@code limit} from 1 to 100, never trimmed. */
  public Page<StoredEvent> listDue(int page, int limit) {
    if (page < 1) {
      throw new InvalidDataException("page", "The page starts at 1");
    }
    if (limit < 1 || limit > 100) {
      throw new InvalidDataException("limit", "The limit is from 1 to 100");
    }
    return events.due(clock.instant(), page, limit);
  }

  public StoredEvent markPublished(UUID eventId) {
    return events.markPublished(eventId, clock.instant()).orElseThrow(OutboxEventNotFoundException::new);
  }

  public StoredEvent recordFailedAttempt(UUID eventId, String error) {
    if (error == null || error.isBlank()) {
      throw new InvalidDataException("error", "The error is required");
    }
    String text = error.strip();
    if (text.length() > 1000 || text.codePoints().anyMatch(OutboxRelay::disguises)) {
      throw new InvalidDataException("error", "The error is at most 1000 characters with no control, format or separator character");
    }
    return events.recordFailedAttempt(eventId, text, clock.instant(), policy).orElseThrow(OutboxEventNotFoundException::new);
  }

  /** What can disguise a line of a log or a screen: control, format, separators, surrogates, unassigned and private-use points. */
  private static boolean disguises(int codePoint) {
    return codePoint != ' ' && switch (Character.getType(codePoint)) {
      case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
          Character.SPACE_SEPARATOR, Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE -> true;
      default -> false;
    };
  }
}
