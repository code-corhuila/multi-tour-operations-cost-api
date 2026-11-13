package co.edu.corhuila.multi_tour.operations_cost.core.security;

/**
 * The roles of a token. PLATFORM_ADMINISTRATOR and SERVICE belong to no tenant; the others belong to the tenant of
 * the token. Which role opens which route is decided per route (spec 002).
 */
public enum Role {
  PLATFORM_ADMINISTRATOR,
  SERVICE,
  FINAL_CUSTOMER,
  TENANT_ADMINISTRATOR,
  OPERATIONAL_COLLABORATOR,
  MANAGER,
  ACCOUNTANT,
  ANALYST;

  /** True for the roles that act inside a tenant. */
  public boolean belongsToATenant() {
    return this != PLATFORM_ADMINISTRATOR && this != SERVICE;
  }
}
