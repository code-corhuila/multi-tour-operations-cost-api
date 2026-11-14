package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: the vehicles of this domain and their assignment to departures. Every read filters by tenant. */
public interface Vehicles {

  void save(Vehicle vehicle);

  /** Whether the tenant has a vehicle with that name, ignoring upper and lower case and the spaces around it. */
  boolean nameTaken(UUID tenantId, String name);

  /** One page of the vehicles of the tenant, sorted by name; {@code page} from 1. */
  Page<Vehicle> list(UUID tenantId, int page, int limit);

  /**
   * The vehicles of the tenant among those ids, locked until the end of the transaction in a fixed order so that two requests
   * never wait on each other. An id that is not there, or is of another tenant, is simply absent from the answer.
   */
  List<Vehicle> lockAll(UUID tenantId, Collection<UUID> vehicleIds);

  List<Vehicle> ofDeparture(UUID tenantId, UUID departureId);

  /**
   * The code of a departure, other than {@code except}, that is {@code SCHEDULED} or {@code IN_EXECUTION}, has one of those
   * vehicles and whose interval overlaps from {@code start} to {@code end}; two intervals that only touch do not overlap.
   */
  Optional<String> overlapping(UUID tenantId, Collection<UUID> vehicleIds, Instant start, Instant end, UUID except);

  /** Assigns the vehicles to the departure; one that is already there stays as it was. */
  void assign(UUID tenantId, UUID departureId, Collection<UUID> vehicleIds, UUID assignedBy, Instant assignedAt);
}
