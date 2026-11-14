package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;

/** Reads one departure of the tenant; one of another tenant is "not found" (HU-OPS-003, AC-API-04.3). */
public final class GetDeparture {

  private final DepartureRepository departures;
  private final DepartureViews views;
  private final ServicesReader services;
  private final PersonalEmergencies emergencies;

  public GetDeparture(DepartureRepository departures, DepartureViews views, ServicesReader services, PersonalEmergencies emergencies) {
    this.departures = departures;
    this.views = views;
    this.services = services;
    this.emergencies = emergencies;
  }

  public record Query(Caller caller, String departureCode) {}

  /**
   * The departure, its reservations with what they received unless their owner could not be read (api 006 D-04), and its personal
   * emergencies (HU-OPS-008), which are this service's own record and are always answered.
   */
  public record Found(DepartureView view, java.util.Optional<ServicesView> services, java.util.List<PersonalEmergency> emergencies) {}

  public Found execute(Query query) {
    var tenantId = Requests.uuid(query.caller().tenantId());
    Departure departure = departures.findByCode(tenantId, query.departureCode())
        .orElseThrow(() -> new DepartureNotFoundException(query.departureCode()));
    return new Found(views.of(departure), services.read(departure), emergencies.emergenciesOf(tenantId, departure.id()));
  }
}
