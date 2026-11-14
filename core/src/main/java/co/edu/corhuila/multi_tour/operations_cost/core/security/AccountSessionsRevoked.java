package co.edu.corhuila.multi_tour.operations_cost.core.security;

import java.time.Instant;
import java.util.UUID;

/** The fact that the sessions of an account were revoked from an instant on (ADR-004 D5). The tenant is optional, as in the cutoff. */
public record AccountSessionsRevoked(UUID eventId, UUID accountId, UUID tenantId, Instant revokedFrom) {

  public AccountSessionsRevoked {
    if (eventId == null || accountId == null || revokedFrom == null) {
      throw new IllegalArgumentException("A revocation has an event id, an account and an instant");
    }
  }
}
