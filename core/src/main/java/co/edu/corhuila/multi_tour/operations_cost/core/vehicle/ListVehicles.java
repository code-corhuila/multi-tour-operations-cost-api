package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Requests;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;

/** Lists the vehicles of the tenant, a page at a time (HU-OPS-002, AC1). A limit out of range is refused, never trimmed (Annex C). */
public final class ListVehicles {

  private static final int MAX_LIMIT = 100;

  private final Vehicles vehicles;

  public ListVehicles(Vehicles vehicles) {
    this.vehicles = vehicles;
  }

  public record Query(Caller caller, int page, int limit) {}

  public Page<Vehicle> execute(Query query) {
    var tenantId = Requests.uuid(query.caller().tenantId());
    if (query.page() < 1) {
      throw new InvalidDataException("page", "The page starts at 1");
    }
    if (query.limit() < 1 || query.limit() > MAX_LIMIT) {
      throw new InvalidDataException("limit", "The limit must be between 1 and " + MAX_LIMIT);
    }
    return vehicles.list(tenantId, query.page(), query.limit());
  }
}
