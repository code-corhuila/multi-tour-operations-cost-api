package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import java.util.List;
import java.util.Optional;

/**
 * What the other domains and the vehicles say about a departure, read before a transaction opens so that a failure writes nothing.
 * It builds the view of the departure as it is read or as a change made it. A tour that left the catalog answers capacity 0 and a
 * departure with no row in commercial-reservation yet has no seats held (gaps G-API-7 and G-API4-5).
 */
public record DepartureReads(Optional<CatalogTour> tour, Optional<Reservations.Seats> seats, List<Vehicle> vehicles) {

  public DepartureView view(Departure departure) {
    return DepartureView.of(departure, tour.map(CatalogTour::capacity).orElse(0), tour.map(CatalogTour::transportRequired).orElse(false),
        seats.map(Reservations.Seats::held).orElse(0), vehicles);
  }
}
