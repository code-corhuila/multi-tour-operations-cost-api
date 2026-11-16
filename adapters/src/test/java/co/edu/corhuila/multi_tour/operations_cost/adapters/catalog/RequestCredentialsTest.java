package co.edu.corhuila.multi_tour.operations_cost.adapters.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Who is calling decides the route of the catalog: a route that services call us on names the tenant in its path, and nothing else does. */
class RequestCredentialsTest {

  private static final String TENANT = "00000000-0000-4000-8000-0000000000aa";

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static String tenantOf(String uri) {
    HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(RequestCredentialsTest.class.getClassLoader(),
        new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> method.getName().equals("getRequestURI") ? uri : null);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return new RequestCredentials().serviceTenantId();
  }

  @Test
  void A_a_route_that_a_service_calls_gives_the_tenant_of_its_path() {
    assertThat(tenantOf("/api/v1/operations-cost/internal/tenants/" + TENANT + "/departures/SAL-001/vehicle-changes/preview")).isEqualTo(TENANT);
  }

  @Test
  void A_a_route_of_a_person_or_a_path_that_is_not_ours_or_a_tenant_that_is_not_an_id_gives_none() {
    assertThat(tenantOf("/api/v1/operations-cost/departures/SAL-001")).isNull();
    assertThat(tenantOf("/other/api/v1/operations-cost/internal/tenants/" + TENANT + "/departures")).isNull();
    assertThat(tenantOf("/api/v1/operations-cost/internal/tenants/not-a-tenant-not-a-tenant-not-a-tenant-12/departures")).isNull();
  }

  @Test
  void A_outside_a_request_there_is_no_tenant() {
    RequestContextHolder.resetRequestAttributes();
    assertThat(new RequestCredentials().serviceTenantId()).isNull();
  }
}
