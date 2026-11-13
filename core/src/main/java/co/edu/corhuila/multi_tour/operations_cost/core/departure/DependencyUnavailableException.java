package co.edu.corhuila.multi_tour.operations_cost.core.departure;

/** Another domain that the operation has to read did not answer well. Nothing was written; the HTTP adapter makes it a 503. */
public class DependencyUnavailableException extends RuntimeException {

  public DependencyUnavailableException(String dependency) {
    super("The " + dependency + " is not available");
  }
}
