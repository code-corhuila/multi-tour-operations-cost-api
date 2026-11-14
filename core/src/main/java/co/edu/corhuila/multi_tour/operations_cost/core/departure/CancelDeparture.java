package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Cancels a {@code SCHEDULED} departure for a cause not attributable to the customer: the first step of the saga {@code cancel-departure}
 * (HU-OPS-006, api spec 010). It is idempotent by state: a departure already cancelled answers its original instant, whatever the key, and
 * writes nothing, so a saga that was resumed, or that runs after a regularization as not performed, gets the same answer (CA-029). It
 * never looks at the state of the tenant nor reads another domain: a departure of an inactive tenant is cancelled all the same (RN-EJE-001).
 * The cancellation, its event and the key are written together with the departure locked. The saga, not a person, calls it: the tenant
 * comes from the route, and who asked and with which role are optional fields the saga may send.
 */
public final class CancelDeparture {

  private final DepartureRepository departures;
  private final Outbox outbox;
  private final IdempotencyKeys keys;
  private final IdGenerator ids;
  private final Transaction transaction;
  private final Clock clock;

  public CancelDeparture(DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, IdGenerator ids, Transaction transaction, Clock clock) {
    this.departures = departures;
    this.outbox = outbox;
    this.keys = keys;
    this.ids = ids;
    this.transaction = transaction;
    this.clock = clock;
  }

  public record Command(
      UUID tenantId, String departureCode, String idempotencyKey, Cancellation.Cause cause, UUID requestedBy, Role requestedByRole,
      String correlationId) {}

  /** The id of the departure and the instant it was cancelled, and whether this request cancelled it (false for an answer of what was). */
  public record Cancelled(UUID departureId, Instant cancelledAt, boolean cancelledNow) {}

  public Cancelled execute(Command command) {
    UUID tenantId = command.tenantId();
    Requests.requireKey(command.idempotencyKey());
    Requests.requireTrace(command.correlationId());
    if (command.cause() != Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER) {
      throw new InvalidDataException("cause", "The cause of this cancellation is NOT_ATTRIBUTABLE_TO_CUSTOMER");
    }
    Departure departure = departures.findByCode(tenantId, command.departureCode())
        .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    if (departure.status() == DepartureStatus.CANCELLED) {
      return original(departure);
    }
    Optional<UUID> previous = keys.departureOf(tenantId, IdempotencyKeys.CANCEL_DEPARTURE, command.idempotencyKey());
    if (previous.isPresent() && !previous.get().equals(departure.id())) {
      throw new BusinessRuleViolationException("The Idempotency-Key was already used for another departure");
    }
    Instant now = clock.instant();
    // the state is judged before the role and before anything is written, and again inside the transaction with the departure locked
    departure.cancel(command.cause(), null, command.requestedBy(), now);
    requireAllowed(command, departure);
    try {
      return cancelLocked(command, departure, now);
    } catch (DuplicateKeyException lostTheRace) {
      // a request with the same key registered it an instant ago: the same departure answers what that one did, another one is a misuse of the key
      Optional<UUID> winner = keys.departureOf(tenantId, IdempotencyKeys.CANCEL_DEPARTURE, command.idempotencyKey());
      if (winner.isEmpty()) {
        throw lostTheRace;
      }
      if (!winner.get().equals(departure.id())) {
        throw new BusinessRuleViolationException("The Idempotency-Key was already used for another departure");
      }
      return departures.find(tenantId, departure.id()).filter(d -> d.status() == DepartureStatus.CANCELLED).map(CancelDeparture::original).orElseThrow(() -> lostTheRace);
    }
  }

  private Cancelled cancelLocked(Command command, Departure departure, Instant now) {
    UUID tenantId = command.tenantId();
    return transaction.run(() -> {
      Departure current = departures.findForUpdate(tenantId, departure.id())
          .orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
      if (current.status() == DepartureStatus.CANCELLED) {
        return original(current);
      }
      Departure next = current.cancel(command.cause(), null, command.requestedBy(), now);
      if (!departures.updateExecution(current, next)) {
        throw new InvalidStatusTransitionException(current.status(), "be cancelled");
      }
      keys.register(tenantId, IdempotencyKeys.CANCEL_DEPARTURE, command.idempotencyKey(), next.id());
      outbox.add(DepartureCancellationEvents.cancelled(ids.next(), next, command.correlationId(), now));
      return new Cancelled(next.id(), next.execution().cancellation().at(), true);
    });
  }

  /**
   * The system (no role) and the Administrator cancel any scheduled departure; a collaborator only one that nobody started and whose end
   * passed (marked). Any other role is refused: a list of who may, never of who may not.
   */
  private static void requireAllowed(Command command, Departure departure) {
    Role role = command.requestedByRole();
    boolean allowed = role == null || role == Role.TENANT_ADMINISTRATOR
        || (role == Role.OPERATIONAL_COLLABORATOR && departure.execution().pendingRegularization());
    if (!allowed) {
      throw new CallerNotAllowedException();
    }
  }

  private static Cancelled original(Departure cancelled) {
    return new Cancelled(cancelled.id(), cancelled.execution().cancellation().at(), false);
  }
}
