package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.Optional;
import java.util.UUID;

/** Port: the departures of this domain. Every read filters by tenant. */
public interface DepartureRepository {

  /** The next code {@code SAL-nnn} of the tenant; called inside the transaction that saves the departure. */
  String nextCode(UUID tenantId);

  void save(Departure departure);

  Optional<Departure> find(UUID tenantId, UUID id);

  Optional<Departure> findByCode(UUID tenantId, String code);

  /**
   * Replaces the start and the estimated end that were read ({@code from}) by the new ones ({@code to}), only if the departure
   * is still {@code SCHEDULED} and still has the dates that were read. False when it is not there, has left that state or was
   * changed meanwhile, so a change decided on a stale reading is never written and two changes never both win.
   */
  boolean updateSchedule(Departure from, Departure to);
}
