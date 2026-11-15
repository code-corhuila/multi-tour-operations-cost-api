package co.edu.corhuila.multi_tour.operations_cost.core.departure;

/** The state of a reservation as commercial-reservation says it, in the words of the contract of this service. */
public enum ReservationState {
  ELIGIBLE,
  NOT_ELIGIBLE,
  IN_EXECUTION,
  FINISHED,
  CANCELLED
}
