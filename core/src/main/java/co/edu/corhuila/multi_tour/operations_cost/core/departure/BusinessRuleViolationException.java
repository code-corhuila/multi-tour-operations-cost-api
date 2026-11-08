package co.edu.corhuila.multi_tour.operations_cost.core.departure;

/** A rule of the business refuses the action; the words are written for the caller. The HTTP adapter makes it a 422. */
public class BusinessRuleViolationException extends RuntimeException {

  public BusinessRuleViolationException(String message) {
    super(message);
  }
}
