package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Clock;
import java.time.Instant;

/**
 * Marks the open departures of every tenant whose estimated end has passed with no closing (HU-OPS-003 part B, AC-API-05.2 to
 * -05.4) for job 3. The mark is not a state: time never closes a departure (RN-EJE-001). Safe to repeat: a departure that is already
 * marked is not chosen again. The batch is bounded, oldest estimated end first, and each mark is written in one transaction with its
 * event, so a failure marks nothing.
 */
public final class MarkPendingRegularization {

  /** The largest batch of one ask. */
  public static final int MAX_LIMIT = 500;

  private final DepartureRepository departures;
  private final Outbox outbox;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  public MarkPendingRegularization(DepartureRepository departures, Outbox outbox, Transaction transaction, IdGenerator ids, Clock clock) {
    this.departures = departures;
    this.outbox = outbox;
    this.transaction = transaction;
    this.ids = ids;
    this.clock = clock;
  }

  public record Command(Caller caller, int limit, String correlationId) {}

  /** How many departures this ask marked. */
  public int execute(Command command) {
    if (!command.caller().roles().contains(Role.SERVICE)) {
      throw new CallerNotAllowedException();
    }
    if (command.limit() < 1 || command.limit() > MAX_LIMIT) {
      throw new InvalidDataException("limit", "The limit is a whole number from 1 to " + MAX_LIMIT);
    }
    Instant now = clock.instant();
    return transaction.run(() -> {
      var due = departures.lockOpenPastEnd(now, command.limit());
      for (Departure current : due) {
        Departure marked = current.markPending(now);
        if (!departures.updateExecution(current, marked)) {
          throw new InvalidStatusTransitionException(current.status(), "be marked pending regularization");
        }
        outbox.add(ExecutionEvents.markedPending(ids.next(), marked, command.correlationId(), now));
      }
      return due.size();
    });
  }
}
