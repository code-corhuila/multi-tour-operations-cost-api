package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the operational costs of a departure with their total (HU-OPS-007, AC-API-07.7). The total is the sum of the lines and is never
 * stored. The travelers served and the catalogued cost come with it so a screen knows whether a cost can be calculated.
 */
public final class ListCosts {

  private final DepartureRepository departures;
  private final OperationalCosts costs;
  private final ServiceRecords services;
  private final Catalog catalog;

  public ListCosts(DepartureRepository departures, OperationalCosts costs, ServiceRecords services, Catalog catalog) {
    this.departures = departures;
    this.costs = costs;
    this.services = services;
    this.catalog = catalog;
  }

  public record Query(Caller caller, String departureCode) {}

  /** {@code baseCostCents} is empty when the tour has no parametrized cost. */
  public record Sheet(List<OperationalCost> costs, long totalCents, int travelersServed, Optional<Long> baseCostCents) {}

  public Sheet execute(Query query) {
    UUID tenantId = Requests.uuid(query.caller().tenantId());
    Departure departure =
        departures.findByCode(tenantId, query.departureCode()).orElseThrow(() -> new DepartureNotFoundException(query.departureCode()));
    Optional<Long> base = catalog.tour(departure.serviceId()).flatMap(CatalogTour::baseOperationalCostCents);
    List<OperationalCost> lines = costs.costsOf(tenantId, departure.id());
    long total = lines.stream().mapToLong(OperationalCost::amountCents).sum();
    return new Sheet(lines, total, ServiceRecord.realBase(services.recordedFor(tenantId, departure.id())), base);
  }
}
