package co.edu.corhuila.multi_tour.operations_cost.core.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * From when the sessions of an account no longer count (ADR-004 D5). The tenant is null for the account of a Platform Administrator,
 * which belongs to no tenant.
 */
public record AccountSessionCutoff(UUID accountId, UUID tenantId, Instant revokedFrom) {

  public AccountSessionCutoff {
    if (accountId == null || revokedFrom == null) {
      throw new IllegalArgumentException("A cutoff has an account and an instant");
    }
  }

  /**
   * Whether a token issued at {@code issuedAt} no longer counts (RN-OPS-API-131). Both sides in whole seconds, the precision of the token: a
   * sign-in in the second of the revocation is a new session, as in identity-audit. A token with no iat cannot be shown to be later.
   */
  public boolean refuses(Instant issuedAt) {
    return issuedAt == null || issuedAt.truncatedTo(ChronoUnit.SECONDS).isBefore(revokedFrom.truncatedTo(ChronoUnit.SECONDS));
  }
}
