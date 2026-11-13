package co.edu.corhuila.multi_tour.operations_cost.core.security;

/** The caller's role or tenant does not allow the operation. The HTTP adapter makes it a 403. */
public class CallerNotAllowedException extends RuntimeException {

  public CallerNotAllowedException() {
    super("The token does not allow this");
  }
}
