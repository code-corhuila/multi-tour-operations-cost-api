package co.edu.corhuila.multi_tour.operations_cost.core.security;

/**
 * The special permissions of the PDR (section 9) this service reads from the claim {@code permissions} of the token, spelled as
 * the security microservice issues them. Only the one this service needs is named here.
 */
public final class Permission {

  /** "Gestionar ejecución de salidas": list, read, start and finish departures (RN-EJE-001). */
  public static final String MANAGE_DEPARTURE_EXECUTION = "MANAGE_DEPARTURE_EXECUTION";

  private Permission() {}
}
