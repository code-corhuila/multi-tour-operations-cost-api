package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Transaction;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One database transaction: everything the work writes through the other adapters commits together or not at all. */
public final class JdbcTransaction implements Transaction {

  private final TransactionTemplate template;

  public JdbcTransaction(PlatformTransactionManager manager) {
    this.template = new TransactionTemplate(manager);
  }

  @Override
  public <T> T run(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
