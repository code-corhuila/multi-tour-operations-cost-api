package co.edu.corhuila.multi_tour.operations_cost.adapters.security;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Judges the role before the handler reads the request. The internal routes (the outbox that job 7 reads) answer only the
 * SERVICE role (ADR-004 D5); every other route of the API is for the Tenant Administrator until a story opens it to another role
 * (spec 002, RN-OPS-API-16). A service opens nothing but its own routes, and no person opens them. A token that carried both kinds of role would open both groups: the security microservice must never issue one. A path written in a disguised way (see {@link RouteGuard#isDisguised}) is
 * refused to everyone: no route needs one.
 */
public final class RouteAuthorization implements HandlerInterceptor {

  private static final String INTERNAL = "/api/v1/operations-cost/internal/";
  private static final Set<Role> PEOPLE = Set.of(Role.TENANT_ADMINISTRATOR);
  private static final Set<Role> SERVICES = Set.of(Role.SERVICE);

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (RouteGuard.isDisguised(request.getRequestURI())
        || !(request.getAttribute(JwtAuthFilter.CALLER) instanceof Caller who)
        || who.roles().stream().noneMatch((request.getRequestURI().startsWith(INTERNAL) ? SERVICES : PEOPLE)::contains)) {
      throw new CallerNotAllowedException();
    }
    return true;
  }
}
