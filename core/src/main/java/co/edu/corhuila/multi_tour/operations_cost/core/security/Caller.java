package co.edu.corhuila.multi_tour.operations_cost.core.security;

import java.util.Set;

/**
 * Who is calling: an account or the name of a repository, its roles and its tenant. The tenant of a request is this one and
 * no other (RN-OPS-API-02). The role of the platform or of a service is never mixed with a role of a tenant, and a role of a
 * tenant always comes with one.
 */
public record Caller(String subject, String tenantId, Set<Role> roles, Set<String> permissions) {

  /** A caller whose token carries no permissions: the platform, the services and the people with none. */
  public Caller(String subject, String tenantId, Set<Role> roles) {
    this(subject, tenantId, roles, Set.of());
  }

  public Caller {
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("A caller has a subject");
    }
    if (roles == null || roles.isEmpty()) {
      throw new IllegalArgumentException("A caller has at least one role");
    }
    roles = Set.copyOf(roles);
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId;
    boolean ofTenant = roles.stream().anyMatch(Role::belongsToATenant);
    if (ofTenant && roles.stream().anyMatch(r -> !r.belongsToATenant())) {
      throw new IllegalArgumentException("The role of the platform or of a service is never mixed with a role of a tenant");
    }
    if (ofTenant && tenantId == null) {
      throw new IllegalArgumentException("A role of a tenant needs the tenant");
    }
    if (!ofTenant && tenantId != null) {
      throw new IllegalArgumentException("The platform and the services have no tenant");
    }
  }

  public boolean holds(String permission) {
    return permissions.contains(permission);
  }
}
