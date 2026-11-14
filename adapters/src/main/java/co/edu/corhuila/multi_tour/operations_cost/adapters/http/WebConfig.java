package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.adapters.security.RouteAuthorization;
import co.edu.corhuila.multi_tour.operations_cost.core.security.ExceptionalAccessAudit;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {

  private final ExceptionalAccessAudit audit;

  WebConfig(ExceptionalAccessAudit audit) {
    this.audit = audit;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new RouteAuthorization(audit)).addPathPatterns("/api/v1/operations-cost/**");
  }
}
