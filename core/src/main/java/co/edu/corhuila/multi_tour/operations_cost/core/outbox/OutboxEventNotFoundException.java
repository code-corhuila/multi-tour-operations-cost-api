package co.edu.corhuila.multi_tour.operations_cost.core.outbox;

/** There is no such event in the outbox. The HTTP adapter makes it a 404. */
public class OutboxEventNotFoundException extends RuntimeException {

  public OutboxEventNotFoundException() {
    super("There is no such event");
  }
}
