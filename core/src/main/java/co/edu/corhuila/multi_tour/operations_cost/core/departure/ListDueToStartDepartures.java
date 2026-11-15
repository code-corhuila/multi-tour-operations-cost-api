package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The codes of the {@code SCHEDULED} departures of one tenant whose start has passed, oldest start first, for job 3 to start their
 * cancellation when the tenant is inactive (job 3 spec 002, RN-OPS-API-140). It never judges the state of the tenant, which belongs to
 * identity-audit, and writes nothing. The tenant comes from the route: only the service role reaches it.
 */
public final class ListDueToStartDepartures {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  private final DepartureRepository departures;
  private final Clock clock;

  public ListDueToStartDepartures(DepartureRepository departures, Clock clock) {
    this.departures = departures;
    this.clock = clock;
  }

  public record Command(Caller caller, UUID tenantId, int limit) {}

  public List<String> execute(Command command) {
    if (!command.caller().roles().contains(Role.SERVICE)) {
      throw new CallerNotAllowedException();
    }
    if (command.limit() < 1 || command.limit() > MAX_LIMIT) {
      throw new InvalidDataException("limit", "The limit is a whole number from 1 to " + MAX_LIMIT);
    }
    return departures.dueToStart(command.tenantId(), clock.instant(), command.limit());
  }
}
