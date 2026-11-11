package co.edu.corhuila.multi_tour.operations_cost.core.departure;

/** The two rules that adding, previewing and replacing the vehicles of a departure all judge first (HU-OPS-002), kept in one place. */
final class VehicleChangeRules {

  private VehicleChangeRules() {}

  /** Only a {@code SCHEDULED} departure changes its vehicles; {@code action} finishes the refusal ("cannot ..."). */
  static void requireScheduled(Departure departure, String action) {
    if (departure.status() != DepartureStatus.SCHEDULED) {
      throw new InvalidStatusTransitionException(departure.status(), action);
    }
  }

  /** The tour of the departure as the catalog says it, refused unless it requires transport: only then do vehicles decide the seats. */
  static CatalogTour requireTransportTour(Catalog catalog, Departure departure) {
    CatalogTour tour = catalog.tour(departure.serviceId())
        .orElseThrow(() -> new BusinessRuleViolationException("The tour is not in the catalog of the tenant"));
    if (!tour.transportRequired()) {
      throw new BusinessRuleViolationException("The tour does not require transport, so it takes no vehicles");
    }
    return tour;
  }
}
