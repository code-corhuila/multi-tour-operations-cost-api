package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.UUID;

/** A reservation of a departure as its owner, commercial-reservation, says it: this service reads it and never keeps the travelers (ADR-004 D4). */
public record ReservationOfDeparture(UUID reservationId, String customer, int travelers, ReservationState state) {}
