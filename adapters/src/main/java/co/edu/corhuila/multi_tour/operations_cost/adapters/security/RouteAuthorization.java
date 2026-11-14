package co.edu.corhuila.multi_tour.operations_cost.adapters.security;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Permission;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Judges the role before the handler reads the request. The internal routes (the outbox that job 7 reads) answer only the
 * SERVICE role (ADR-004 D5); the four routes that list, read, start and finish a departure are for whoever holds the permission
 * "Gestionar ejecución de salidas" (HU-OPS-003); every other route of the API is for the Tenant Administrator until a story opens it to another role
 * (spec 002, RN-OPS-API-16). A service opens nothing but its own routes, and no person opens them. A token that carried both kinds of role would open both groups: the security microservice must never issue one. A path written in a disguised way (see {@link RouteGuard#isDisguised}) is
 * refused to everyone: no route needs one.
 */
public final class RouteAuthorization implements HandlerInterceptor {

  private static final String INTERNAL = "/api/v1/operations-cost/internal/";
  private static final Set<Role> PEOPLE = Set.of(Role.TENANT_ADMINISTRATOR);
  private static final Set<Role> SERVICES = Set.of(Role.SERVICE);
  // Listing, reading, starting and finishing a departure are for whoever holds the permission, among the people who work in a tenant
  // and are not a customer nor an optional role (api 004 RN-OPS-API-46).
  private static final Set<Role> EXECUTORS = Set.of(Role.TENANT_ADMINISTRATOR, Role.OPERATIONAL_COLLABORATOR);
  private static final Pattern EXECUTION_ROUTE =
      Pattern.compile("^" + Pattern.quote("/api/v1/operations-cost/departures") + "(/SAL-[0-9]{3,6}(/(executions|finish))?)?$");

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(request.getAttribute(JwtAuthFilter.CALLER) instanceof Caller who) || !allows(request.getMethod(), request.getRequestURI(), who)) {
      throw new CallerNotAllowedException();
    }
    return true;
  }

  /** The decision with no server: whether this caller opens this method and path. */
  static boolean allows(String method, String uri, Caller who) {
    if (RouteGuard.isDisguised(uri)) {
      return false;
    }
    if (uri.startsWith(INTERNAL)) {
      return who.roles().stream().anyMatch(SERVICES::contains);
    }
    if (isExecutionRoute(method, uri)) {
      return who.holds(Permission.MANAGE_DEPARTURE_EXECUTION) && who.roles().stream().anyMatch(EXECUTORS::contains);
    }
    return who.roles().stream().anyMatch(PEOPLE::contains);
  }

  private static boolean isExecutionRoute(String method, String uri) {
    if (!EXECUTION_ROUTE.matcher(uri).matches()) {
      return false;
    }
    return uri.endsWith("/executions") || uri.endsWith("/finish") ? "POST".equals(method) : "GET".equals(method);
  }
}
