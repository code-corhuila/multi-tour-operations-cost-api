package co.edu.corhuila.multi_tour.operations_cost.core.departure;

/** The state of the departure does not allow the action. The HTTP adapter makes it a 422 INVALID_STATUS_TRANSITION. */
public class InvalidStatusTransitionException extends RuntimeException {

  public InvalidStatusTransitionException(DepartureStatus from, String action) {
    super("A departure that is " + from.name() + " cannot " + action);
  }
}
