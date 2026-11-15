package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.List;
import java.util.UUID;

/** Port: the extraordinary cancellations of the reservations of a departure (HU-OPS-005). A reservation is cancelled once and the record is never rewritten. */
public interface ExtraordinaryCancellations {

  void add(UUID tenantId, UUID departureId, ExtraordinaryCancellation cancellation);

  boolean exists(UUID tenantId, UUID departureId, UUID reservationId);

  List<ExtraordinaryCancellation> cancellationsOf(UUID tenantId, UUID departureId);
}
