package co.edu.corhuila.multi_tour.operations_cost.adapters.security;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Judges the role before the handler reads the request. Every route of the API is for the Tenant Administrator until a story
 * opens it to another role (spec 002, RN-OPS-API-16). A path written in a disguised way (see {@link RouteGuard#isDisguised}) is
 * refused to everyone: no route needs one.
 */
public final class RouteAuthorization implements HandlerInterceptor {

  private static final Set<Role> ALLOWED = Set.of(Role.TENANT_ADMINISTRATOR);

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (RouteGuard.isDisguised(request.getRequestURI())
        || !(request.getAttribute(JwtAuthFilter.CALLER) instanceof Caller who)
        || who.roles().stream().noneMatch(ALLOWED::contains)) {
      throw new CallerNotAllowedException();
    }
    return true;
  }
}
