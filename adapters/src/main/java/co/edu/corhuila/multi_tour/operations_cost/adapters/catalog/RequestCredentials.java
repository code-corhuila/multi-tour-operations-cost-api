package co.edu.corhuila.multi_tour.operations_cost.adapters.catalog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The credential of the request in course, which the catalog adapter forwards: its Authorization header and its correlation id. */
@Component
public class RequestCredentials implements HttpCatalog.Credentials {

  @Override
  public String authorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
      String header = attributes.getRequest().getHeader("Authorization");
      if (header != null) {
        return header;
      }
    }
    throw new IllegalStateException("There is no request whose credential could be forwarded");
  }

  // The routes that services call on this service carry the tenant in their path (/internal/tenants/{tenantId}/...) and only a service reaches them.
  private static final Pattern INTERNAL = Pattern.compile("^/api/v1/operations-cost/internal/tenants/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/");

  @Override
  public String serviceTenantId() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
      Matcher found = INTERNAL.matcher(attributes.getRequest().getRequestURI());
      return found.find() ? found.group(1) : null;
    }
    return null;
  }

  @Override
  public String correlationId() {
    String id = MDC.get("traceId");
    return id == null ? "none" : id;
  }
}
