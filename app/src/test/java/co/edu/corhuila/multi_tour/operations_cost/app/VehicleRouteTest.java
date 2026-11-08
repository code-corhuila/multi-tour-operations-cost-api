package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** POST and GET /vehicles: every scenario that can pass or fail, each answer validated against the contract (api spec 003, AC-API-03.1 to -03.3, -03.10, -03.13). */
@Import(InMemoryWorld.Config.class)
class VehicleRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/operations-cost/vehicles";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");

  private final InMemoryWorld world = InMemoryWorld.WORLD;

  @BeforeEach
  void setUp() {
    world.reset();
  }

  private static String token(String role, UUID tenant, String subject) {
    return Tokens.valid(Tokens.claims(subject, 600, List.of(role), tenant == null ? null : tenant.toString()));
  }

  private static String admin() {
    return token("TENANT_ADMINISTRATOR", TENANT, ACCOUNT.toString());
  }

  private static Map<String, Object> body(String name, Object capacity) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("capacity", capacity);
    return body;
  }

  private static RequestSpecification as(String bearer, String key) {
    RequestSpecification request = given().contentType(ContentType.JSON);
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return request;
  }

  private void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("message")).isNotBlank();
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_registers_a_vehicle_with_201_and_where_the_list_is() {
    Response response = as(admin(), "key-0001-abcdef").filter(Contract.filter()).body(body("Van VAN-204", 12)).post(ROUTE);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.header("Location")).isEqualTo(ROUTE);
    assertThat(response.jsonPath().getString("name")).isEqualTo("Van VAN-204");
    assertThat(response.jsonPath().getInt("capacity")).isEqualTo(12);
    assertThat(UUID.fromString(response.jsonPath().getString("vehicleId"))).isNotNull();
    assertThat(world.vehicleRows).hasSize(1);
  }

  @Test
  void C_create_vehicle_repeat_returns_the_same_vehicle_with_200() {
    Response first = as(admin(), "key-0001-abcdef").filter(Contract.filter()).body(body("Van 1", 12)).post(ROUTE);
    Response repeat = as(admin(), "key-0001-abcdef").filter(Contract.filter()).body(body("Another name", 30)).post(ROUTE);
    assertThat(repeat.statusCode()).isEqualTo(200);
    assertThat(repeat.jsonPath().getString("vehicleId")).isEqualTo(first.jsonPath().getString("vehicleId"));
    assertThat(world.vehicleRows).hasSize(1);
  }

  @Test
  void C_a_missing_or_wrong_idempotency_key_is_400() {
    for (String key : new String[] {null, "short-7", "k".repeat(129)}) {
      Response response = as(admin(), key).filter(Contract.answers()).body(body("Van 1", 12)).post(ROUTE);
      assertError(response, 400, "VALIDATION_ERROR");
      assertThat(response.jsonPath().getString("details[0].field")).isEqualTo("Idempotency-Key");
    }
    assertThat(world.vehicleRows).isEmpty();
  }

  @Test
  void C_a_name_or_a_capacity_out_of_range_is_400_and_names_the_field() {
    for (Map<String, Object> bad : List.of(body("", 12), body("   ", 12), body("n".repeat(61), 12))) {
      Response response = as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(bad).post(ROUTE);
      assertError(response, 400, "VALIDATION_ERROR");
      assertThat(response.jsonPath().getString("details[0].field")).isEqualTo("name");
    }
    for (Object capacity : new Object[] {0, 101, -5}) {
      Response response = as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(body("Van", capacity)).post(ROUTE);
      assertError(response, 400, "VALIDATION_ERROR");
      assertThat(response.jsonPath().getString("details[0].field")).isEqualTo("capacity");
    }
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(body("Van", "twelve")).post(ROUTE), 400, "VALIDATION_ERROR");
    assertThat(world.vehicleRows).isEmpty();
  }

  @Test
  void C_an_undeclared_field_is_400() {
    Map<String, Object> withTenant = body("Van", 12);
    withTenant.put("tenantId", OTHER_TENANT.toString());
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(withTenant).post(ROUTE), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_a_name_already_used_is_422_ignoring_case_and_spaces() {
    as(admin(), "key-0001-abcdef").filter(Contract.filter()).body(body("Van 1", 12)).post(ROUTE);
    assertError(as(admin(), "key-0002-abcdef").filter(Contract.answers()).body(body("  van 1 ", 10)).post(ROUTE), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(world.vehicleRows).hasSize(1);
  }

  @Test
  void C_no_token_is_401_and_only_tenant_administrator_is_allowed() {
    assertError(as(null, "key-0001-abcdef").filter(Contract.answers()).body(body("Van", 12)).post(ROUTE), 401, "UNAUTHORIZED");
    assertError(as(null, null).filter(Contract.answers()).get(ROUTE), 401, "UNAUTHORIZED");
    for (String role : List.of("OPERATIONAL_COLLABORATOR", "MANAGER", "ACCOUNTANT", "ANALYST", "FINAL_CUSTOMER")) {
      String bearer = token(role, TENANT, ACCOUNT.toString());
      assertError(as(bearer, "key-0001-abcdef").filter(Contract.answers()).body(body("Van", 12)).post(ROUTE), 403, "FORBIDDEN");
      assertError(as(bearer, null).filter(Contract.answers()).get(ROUTE), 403, "FORBIDDEN");
    }
    assertError(as(token("SERVICE", null, "event-relay"), null).filter(Contract.answers()).get(ROUTE), 403, "FORBIDDEN");
    assertThat(world.vehicleRows).isEmpty();
  }

  @Test
  void C_a_correlation_id_sent_is_the_trace_id_of_the_error() {
    Response response = as(admin(), "short").header("X-Correlation-Id", "trace-from-the-caller").filter(Contract.answers())
        .body(body("Van", 12)).post(ROUTE);
    assertThat(response.jsonPath().getString("traceId")).isEqualTo("trace-from-the-caller");
  }

  @Test
  void C_lists_the_vehicles_of_the_tenant_sorted_by_name_in_pages() {
    int n = 0;
    for (String name : List.of("bus", "Van A", "Boat")) {
      as(admin(), "key-000" + (++n) + "-abcdef").body(body(name, 10)).post(ROUTE);
    }
    as(token("TENANT_ADMINISTRATOR", OTHER_TENANT, ACCOUNT.toString()), "key-other-abcdef").body(body("Other", 5)).post(ROUTE);
    Response first = as(admin(), null).filter(Contract.filter()).queryParam("page", 1).queryParam("limit", 2).get(ROUTE);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.jsonPath().getList("data.name")).containsExactly("Boat", "bus");
    assertThat(first.jsonPath().getInt("meta.total")).isEqualTo(3);
    assertThat(first.jsonPath().getInt("meta.totalPages")).isEqualTo(2);
    Response second = as(admin(), null).filter(Contract.filter()).queryParam("page", 2).queryParam("limit", 2).get(ROUTE);
    assertThat(second.jsonPath().getList("data.name")).containsExactly("Van A");
    Response defaults = as(admin(), null).filter(Contract.filter()).get(ROUTE);
    assertThat(defaults.jsonPath().getInt("meta.limit")).isEqualTo(20);
  }

  @Test
  void C_the_limits_of_the_page_are_400_never_trimmed() {
    for (Object[] bad : new Object[][] {{0, 20}, {1, 0}, {1, 101}}) {
      Response response = as(admin(), null).filter(Contract.answers()).queryParam("page", bad[0]).queryParam("limit", bad[1]).get(ROUTE);
      assertError(response, 400, "VALIDATION_ERROR");
    }
  }
}
