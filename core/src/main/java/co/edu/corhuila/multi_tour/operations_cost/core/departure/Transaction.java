package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.function.Supplier;

/** Port: everything the work writes commits together or not at all. */
public interface Transaction {

  <T> T run(Supplier<T> work);
}
