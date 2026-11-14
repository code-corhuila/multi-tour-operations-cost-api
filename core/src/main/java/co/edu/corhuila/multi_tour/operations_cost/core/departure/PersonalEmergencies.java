package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.List;
import java.util.UUID;

/**
 * Port: the personal emergencies of the reservations of a departure (HU-OPS-008). A reservation has one and the record is never rewritten:
 * an adapter that meets the engine refusing a second row for the same reservation throws {@link DuplicateKeyException}, which the use case
 * turns into the answer of the request that won.
 */
public interface PersonalEmergencies {

  void add(UUID tenantId, UUID departureId, PersonalEmergency emergency);

  boolean hasEmergency(UUID tenantId, UUID departureId, UUID reservationId);

  List<PersonalEmergency> emergenciesOf(UUID tenantId, UUID departureId);
}
