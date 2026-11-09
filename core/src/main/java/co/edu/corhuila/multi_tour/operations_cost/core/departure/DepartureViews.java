package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads what a departure needs to be answered. A list reads each tour once and the seats of each tour once, however many of its
 * departures are on the page (api 004 D-06). When the catalog or commercial-reservation cannot answer, the failure is not hidden.
 */
public final class DepartureViews {

  private final Catalog catalog;
  private final Reservations reservations;
  private final Vehicles vehicles;

  public DepartureViews(Catalog catalog, Reservations reservations, Vehicles vehicles) {
    this.catalog = catalog;
    this.reservations = reservations;
    this.vehicles = vehicles;
  }

  public DepartureReads read(Departure departure) {
    return reads(departure, new HashMap<>(), new HashMap<>());
  }

  public DepartureView of(Departure departure) {
    return read(departure).view(departure);
  }

  public List<DepartureView> ofAll(List<Departure> departures) {
    Map<UUID, Optional<CatalogTour>> tours = new HashMap<>();
    Map<UUID, Map<UUID, Reservations.Seats>> seats = new HashMap<>();
    return departures.stream().map(d -> reads(d, tours, seats).view(d)).toList();
  }

  private DepartureReads reads(Departure departure, Map<UUID, Optional<CatalogTour>> tours, Map<UUID, Map<UUID, Reservations.Seats>> seats) {
    UUID serviceId = departure.serviceId();
    Optional<CatalogTour> tour = tours.computeIfAbsent(serviceId, catalog::tour);
    Map<UUID, Reservations.Seats> ofTour = seats.computeIfAbsent(serviceId, reservations::seatsOfTour);
    return new DepartureReads(tour, Optional.ofNullable(ofTour.get(departure.id())), vehicles.ofDeparture(departure.tenantId(), departure.id()));
  }
}
