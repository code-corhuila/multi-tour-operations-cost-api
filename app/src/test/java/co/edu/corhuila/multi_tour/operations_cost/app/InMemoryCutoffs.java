package co.edu.corhuila.multi_tour.operations_cost.app;

import co.edu.corhuila.multi_tour.operations_cost.core.security.AccountSessionCutoff;
import co.edu.corhuila.multi_tour.operations_cost.core.security.AccountSessionCutoffs;
import co.edu.corhuila.multi_tour.operations_cost.core.security.ProcessedEvents;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The cutoffs and the processed events in memory, so the route and the filter are tried on their own: the SQL has its own tests. */
final class InMemoryCutoffs implements AccountSessionCutoffs, ProcessedEvents {

  static final InMemoryCutoffs STORE = new InMemoryCutoffs();

  final Map<UUID, AccountSessionCutoff> cutoffs = new ConcurrentHashMap<>();
  final Set<UUID> processed = ConcurrentHashMap.newKeySet();
  /** The cutoffs cannot be read, as when the database does not answer. */
  volatile boolean down;

  void reset() {
    cutoffs.clear();
    processed.clear();
    down = false;
  }

  @Override
  public Optional<AccountSessionCutoff> find(UUID accountId) {
    if (down) {
      throw new IllegalStateException("The cutoffs cannot be read");
    }
    return Optional.ofNullable(cutoffs.get(accountId));
  }

  @Override
  public synchronized boolean keepLater(AccountSessionCutoff cutoff) {
    AccountSessionCutoff held = cutoffs.get(cutoff.accountId());
    if (held != null && !held.revokedFrom().isBefore(cutoff.revokedFrom())) {
      return false;
    }
    cutoffs.put(cutoff.accountId(), cutoff);
    return true;
  }

  @Override
  public boolean markIfNew(UUID eventId) {
    return processed.add(eventId);
  }
}
