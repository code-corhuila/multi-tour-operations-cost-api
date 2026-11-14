package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;

/** Reads one departure of the tenant; one of another tenant is "not found" (HU-OPS-003, AC-API-04.3). */
public final class GetDeparture {

  private final DepartureRepository departures;
  private final DepartureViews views;

  public GetDeparture(DepartureRepository departures, DepartureViews views) {
    this.departures = departures;
    this.views = views;
  }

  public record Query(Caller caller, String departureCode) {}

  public DepartureView execute(Query query) {
    var tenantId = Requests.uuid(query.caller().tenantId());
    Departure departure = departures.findByCode(tenantId, query.departureCode())
        .orElseThrow(() -> new DepartureNotFoundException(query.departureCode()));
    return views.of(departure);
  }
}
