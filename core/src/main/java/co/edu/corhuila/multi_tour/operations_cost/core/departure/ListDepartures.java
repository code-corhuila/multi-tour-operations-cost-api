package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.util.Optional;

/** Lists the departures of the tenant, a page at a time, most recent first (HU-OPS-003, AC-API-04.1, -04.2). A limit out of range is refused. */
public final class ListDepartures {

  private static final int MAX_LIMIT = 100;

  private final DepartureRepository departures;
  private final DepartureViews views;

  public ListDepartures(DepartureRepository departures, DepartureViews views) {
    this.departures = departures;
    this.views = views;
  }

  public record Query(Caller caller, Optional<DepartureStatus> status, int page, int limit) {}

  public Page<DepartureView> execute(Query query) {
    var tenantId = Requests.uuid(query.caller().tenantId());
    if (query.page() < 1) {
      throw new InvalidDataException("page", "The page starts at 1");
    }
    if (query.limit() < 1 || query.limit() > MAX_LIMIT) {
      throw new InvalidDataException("limit", "The limit must be between 1 and " + MAX_LIMIT);
    }
    Page<Departure> found = departures.page(tenantId, query.status(), query.page(), query.limit());
    return new Page<>(views.ofAll(found.data()), found.page(), found.limit(), found.total());
  }
}
