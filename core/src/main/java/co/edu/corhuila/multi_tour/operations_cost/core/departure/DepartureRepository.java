package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.Optional;
import java.util.UUID;

/** Port: the departures of this domain. Every read filters by tenant. */
public interface DepartureRepository {

  /** The next code {@code SAL-nnn} of the tenant; called inside the transaction that saves the departure. */
  String nextCode(UUID tenantId);

  void save(Departure departure);

  Optional<Departure> find(UUID tenantId, UUID id);
}
