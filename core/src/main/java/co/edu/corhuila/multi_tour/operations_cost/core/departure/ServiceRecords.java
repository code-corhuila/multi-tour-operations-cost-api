package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Port: what each reservation of a departure received (HU-OPS-004). Saving a reservation again replaces its record. */
public interface ServiceRecords {

  void saveAll(UUID tenantId, UUID departureId, Collection<ServiceRecord> records, UUID by, Instant at);

  List<ServiceRecord> recordedFor(UUID tenantId, UUID departureId);
}
