package co.edu.corhuila.multi_tour.operations_cost.core.departure;

/** Port: writes an event in the same transaction as the change that causes it. */
public interface Outbox {

  void add(OutboxEvent event);
}
