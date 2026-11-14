package co.edu.corhuila.multi_tour.operations_cost.core.security;

import java.util.Optional;
import java.util.UUID;

/** Port: the cutoff of each account. */
public interface AccountSessionCutoffs {

  Optional<AccountSessionCutoff> find(UUID accountId);

  /** Keeps the cutoff only when there is none or it is later than the one held; true when it moved (RN-OPS-API-130). */
  boolean keepLater(AccountSessionCutoff cutoff);
}
