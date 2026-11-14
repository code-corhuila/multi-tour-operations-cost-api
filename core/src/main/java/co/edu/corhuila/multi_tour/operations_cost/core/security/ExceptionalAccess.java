package co.edu.corhuila.multi_tour.operations_cost.core.security;

import java.util.Set;
import java.util.UUID;

/**
 * The claims of the token of an exceptional or emergency access (spec 012, ADR-004 D5): {@code access} is "exceptional" or
 * "emergency", {@code scope} holds the areas it may read (plus {@code SENSITIVE}) and {@code authorizationId} is the id of the access.
 */
public record ExceptionalAccess(String access, Set<String> scope, UUID authorizationId) {

  /** The closed list of areas and the extra word of the sensitive data. */
  public static final Set<String> WORDS = Set.of("RESERVATIONS", "CATALOG", "OPERATIONS", "CASH", "CUSTOMERS", "SENSITIVE");

  public ExceptionalAccess {
    if (!"exceptional".equals(access) && !"emergency".equals(access)) {
      throw new IllegalArgumentException("An access is exceptional or emergency");
    }
    if (scope == null || scope.isEmpty() || !WORDS.containsAll(scope)) {
      throw new IllegalArgumentException("The scope is a list of known words");
    }
    if (authorizationId == null) {
      throw new IllegalArgumentException("An access has its authorization id");
    }
    scope = Set.copyOf(scope);
  }

  public boolean covers(String area) {
    return scope.contains(area);
  }
}
