package co.edu.corhuila.multi_tour.operations_cost.adapters.security;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Permission;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Who opens which route (api 004 RN-OPS-API-46): the decision with no server and no token. */
class RouteAuthorizationTest {

  private static final String BASE = "/api/v1/operations-cost";
  private static final String TENANT = "tenant-1";

  private static Caller person(Role role, String... permissions) {
    return new Caller("account-1", TENANT, Set.of(role), Set.of(permissions));
  }

  private static final String[][] EXECUTION_ROUTES = {
    {"GET", BASE + "/departures"},
    {"GET", BASE + "/departures/SAL-001"},
    {"POST", BASE + "/departures/SAL-001/executions"},
    {"POST", BASE + "/departures/SAL-001/finish"},
  };

  @Test
  void C_the_permission_opens_the_four_execution_routes_to_an_administrator_and_a_collaborator() {
    for (String[] route : EXECUTION_ROUTES) {
      assertThat(RouteAuthorization.allows(route[0], route[1], person(Role.TENANT_ADMINISTRATOR, Permission.MANAGE_DEPARTURE_EXECUTION)))
          .as(route[0] + " " + route[1] + " administrator").isTrue();
      assertThat(RouteAuthorization.allows(route[0], route[1], person(Role.OPERATIONAL_COLLABORATOR, Permission.MANAGE_DEPARTURE_EXECUTION)))
          .as(route[0] + " " + route[1] + " collaborator").isTrue();
    }
  }

  @Test
  void C_without_the_permission_the_four_execution_routes_are_closed_even_to_the_administrator() {
    for (String[] route : EXECUTION_ROUTES) {
      assertThat(RouteAuthorization.allows(route[0], route[1], person(Role.TENANT_ADMINISTRATOR))).as(route[1]).isFalse();
      assertThat(RouteAuthorization.allows(route[0], route[1], person(Role.OPERATIONAL_COLLABORATOR, "CONSULT_SENSITIVE_DATA"))).as(route[1]).isFalse();
    }
  }

  @Test
  void C_a_customer_or_an_optional_role_is_refused_even_with_the_permission() {
    for (Role role : new Role[] {Role.FINAL_CUSTOMER, Role.MANAGER, Role.ACCOUNTANT, Role.ANALYST}) {
      for (String[] route : EXECUTION_ROUTES) {
        assertThat(RouteAuthorization.allows(route[0], route[1], person(role, Permission.MANAGE_DEPARTURE_EXECUTION)))
            .as(role + " " + route[1]).isFalse();
      }
    }
  }

  @Test
  void C_the_routes_of_scheduling_and_vehicles_stay_the_administrators() {
    String[][] others = {
      {"POST", BASE + "/departures"}, {"PUT", BASE + "/departures/SAL-001/schedule"},
      {"POST", BASE + "/departures/SAL-001/vehicles"}, {"GET", BASE + "/vehicles"}, {"POST", BASE + "/vehicles"},
    };
    for (String[] route : others) {
      assertThat(RouteAuthorization.allows(route[0], route[1], person(Role.TENANT_ADMINISTRATOR))).as(route[1]).isTrue();
      assertThat(RouteAuthorization.allows(route[0], route[1], person(Role.OPERATIONAL_COLLABORATOR, Permission.MANAGE_DEPARTURE_EXECUTION)))
          .as(route[1]).isFalse();
    }
  }

  @Test
  void C_a_method_the_execution_routes_do_not_have_is_not_opened_by_the_permission() {
    Caller collaborator = person(Role.OPERATIONAL_COLLABORATOR, Permission.MANAGE_DEPARTURE_EXECUTION);
    assertThat(RouteAuthorization.allows("POST", BASE + "/departures", collaborator)).isFalse();
    assertThat(RouteAuthorization.allows("DELETE", BASE + "/departures/SAL-001", collaborator)).isFalse();
    assertThat(RouteAuthorization.allows("GET", BASE + "/departures/SAL-001/finish", collaborator)).isFalse();
    assertThat(RouteAuthorization.allows("GET", BASE + "/departures/not-a-code", collaborator)).isFalse();
  }

  @Test
  void C_the_internal_routes_are_the_services_and_no_person_opens_them() {
    Caller service = new Caller("event-relay", null, Set.of(Role.SERVICE));
    assertThat(RouteAuthorization.allows("GET", BASE + "/internal/outbox/events", service)).isTrue();
    assertThat(RouteAuthorization.allows("GET", BASE + "/departures", service)).isFalse();
    assertThat(RouteAuthorization.allows("GET", BASE + "/internal/outbox/events",
        person(Role.TENANT_ADMINISTRATOR, Permission.MANAGE_DEPARTURE_EXECUTION))).isFalse();
  }

  @Test
  void C_the_platform_with_the_permission_opens_nothing_and_a_disguised_or_malformed_path_is_refused_to_everyone() {
    Caller platform = new Caller("root", null, Set.of(Role.PLATFORM_ADMINISTRATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));
    for (String[] route : EXECUTION_ROUTES) {
      assertThat(RouteAuthorization.allows(route[0], route[1], platform)).as(route[1]).isFalse();
    }
    Caller administrator = person(Role.TENANT_ADMINISTRATOR, Permission.MANAGE_DEPARTURE_EXECUTION);
    for (String uri : new String[] {BASE + "/departures/SAL-001/../x", BASE + "/departures/SAL-001/%65xecutions", BASE + "//departures",
        BASE + "/departures;x=1"}) {
      assertThat(RouteAuthorization.allows("GET", uri, administrator)).as(uri).isFalse();
    }
    Caller collaborator = person(Role.OPERATIONAL_COLLABORATOR, Permission.MANAGE_DEPARTURE_EXECUTION);
    for (String uri : new String[] {BASE + "/departures/SAL-001/", BASE + "/departures/sal-001", BASE + "/departures/SAL-1", BASE + "/departures/"}) {
      assertThat(RouteAuthorization.allows("GET", uri, collaborator)).as(uri).isFalse();
    }
  }
}
