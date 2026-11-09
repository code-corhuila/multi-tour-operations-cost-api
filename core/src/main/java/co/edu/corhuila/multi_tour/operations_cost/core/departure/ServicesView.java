package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.List;
import java.util.Optional;

/** The reservations of a departure with what was recorded for each, and the real base: the travelers served of the delivered ones. */
public record ServicesView(List<ReservationView> reservations, int travelersServed) {

  public record ReservationView(ReservationOfDeparture reservation, Optional<ServiceRecord> recorded) {}
}
