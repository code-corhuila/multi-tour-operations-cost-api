package co.edu.corhuila.multi_tour.operations_cost.adapters.catalog;

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

  @Override
  public String correlationId() {
    String id = MDC.get("traceId");
    return id == null ? "none" : id;
  }
}
