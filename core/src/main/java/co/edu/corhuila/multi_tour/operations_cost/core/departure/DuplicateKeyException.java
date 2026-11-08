package co.edu.corhuila.multi_tour.operations_cost.core.departure;

/** The idempotency key was registered by another request an instant earlier; the use case answers that request's departure. */
public class DuplicateKeyException extends RuntimeException {

  public DuplicateKeyException() {
    super("The idempotency key is already registered");
  }
}
