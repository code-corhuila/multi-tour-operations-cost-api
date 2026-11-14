package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.Optional;
import java.util.UUID;

/** Port: the seats of a departure as commercial-reservation, which owns the reservations, keeps them (ADR-004 D4). */
public interface Reservations {

  /** The seats of a departure: its total and how many are held by reservations. */
  record Seats(int total, int held) {}

  /**
   * The seats of the departure of that tour, or empty when commercial-reservation has no row of it (it may not have received the
   * event yet). Throws {@link DependencyUnavailableException} when it cannot say.
   */
  Optional<Seats> seatsOf(UUID serviceId, UUID departureId);
}
