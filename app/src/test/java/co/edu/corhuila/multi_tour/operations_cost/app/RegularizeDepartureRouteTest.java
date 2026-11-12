package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** The regularization of a departure that never started (api spec 005): the contract, the permission and every refusal. */
@Import(InMemoryWorld.Config.class)
class RegularizeDepartureRouteTest extends ShellTest {

  private static final String DEPARTURES = "/api/v1/operations-cost/departures";
  private static final String ROUTE = DEPARTURES + "/SAL-001/regularization";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private static final Instant START = NOW.minus(Duration.ofHours(20));
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final String PERMISSION = "MANAGE_DEPARTURE_EXECUTION";
  private static final String KEY = "regularize-key-0001";

  private final InMemoryWorld world = InMemoryWorld.WORLD;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    put(1, TENANT, true);
  }

  private void put(int n, UUID tenant, boolean marked) {
    Departure created = new Departure(new UUID(0, 1000 + n), tenant, String.format("SAL-%03d", n), TOUR, START, END, "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, START.minus(Duration.ofDays(3)));
    world.departures.put(created.id(), marked ? created.markPending(END.plusSeconds(1)) : created);
  }

  private static String token(String role, UUID tenant, String... permissions) {
    var claims = Tokens.claims(ACCOUNT.toString(), 600, List.of(role), tenant.toString());
    return Tokens.valid(permissions.length == 0 ? claims : claims.claim("permissions", List.of(permissions)));
  }

  private static String administrator() {
    return token("TENANT_ADMINISTRATOR", TENANT, PERMISSION);
  }

  private static RequestSpecification as(String bearer) {
    RequestSpecification request = given().contentType(ContentType.JSON);
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return request;
  }

  private static Map<String, Object> body(String result, Instant start, Instant end) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    if (start != null) {
      body.put("actualStart", start.toString());
    }
    if (end != null) {
      body.put("actualEnd", end.toString());
    }
    return body;
  }

  private static Response send(String bearer, String key, Map<String, Object> body, boolean validRequest) {
    RequestSpecification request = as(bearer).filter(validRequest ? Contract.filter() : Contract.answers()).body(body);
    return (key == null ? request : request.header("Idempotency-Key", key)).post(ROUTE);
  }

  private static void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("message")).isNotBlank();
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_an_executed_departure_is_regularized_with_201_and_both_events() {
    Response response = send(administrator(), KEY, body("EXECUTED", START, END), true);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.header("Location")).endsWith(DEPARTURES + "/SAL-001");
    assertThat(response.jsonPath().getString("status")).isEqualTo("FINISHED");
    assertThat(response.jsonPath().getString("actualStart")).isEqualTo(START.toString());
    assertThat(response.jsonPath().getString("actualEnd")).isEqualTo(END.toString());
    assertThat(response.jsonPath().getBoolean("pendingRegularization")).isFalse();
    assertThat(response.jsonPath().getBoolean("interrupted")).isFalse();
    assertThat(world.events).extracting(e -> e.eventType()).containsExactly("SalidaExecutionStarted", "SalidaFinalized");
  }

  @Test
  void C_an_interrupted_departure_with_an_early_start_keeps_its_cause_and_never_cancels() {
    Map<String, Object> request = body("INTERRUPTED", START.minus(Duration.ofMinutes(15)), END);
    request.put("interruption", "Heavy rain closed the trail");
    request.put("earlyStartReason", "The group left early");
    Response response = send(administrator(), KEY, request, true);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.jsonPath().getString("status")).isEqualTo("FINISHED");
    assertThat(response.jsonPath().getBoolean("interrupted")).isTrue();
    assertThat(response.jsonPath().getString("interruptionCause")).isEqualTo("Heavy rain closed the trail");
  }

  @Test
  void C_the_same_key_again_answers_200_with_the_departure_already_regularized() {
    send(administrator(), KEY, body("EXECUTED", START, END), true);
    Response again = send(administrator(), KEY, body("EXECUTED", START, END), true);
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getString("status")).isEqualTo("FINISHED");
    assertThat(world.events).hasSize(2);
  }

  @Test
  void C_a_request_that_is_not_valid_is_400_by_field() {
    assertError(send(administrator(), null, body("EXECUTED", START, END), false), 400, "VALIDATION_ERROR");
    assertError(send(administrator(), KEY, body("DONE", START, END), false), 400, "VALIDATION_ERROR");
    assertError(send(administrator(), KEY, body("EXECUTED", null, END), false), 400, "VALIDATION_ERROR");
    assertError(send(administrator(), KEY, body("EXECUTED", START, null), false), 400, "VALIDATION_ERROR");
    assertError(send(administrator(), KEY, body("INTERRUPTED", START, END), false), 400, "VALIDATION_ERROR");
    Map<String, Object> needless = body("EXECUTED", START, END);
    needless.put("interruption", "Rain");
    assertError(send(administrator(), KEY, needless, false), 400, "VALIDATION_ERROR");
    assertError(send(administrator(), KEY, body("EXECUTED", START.minusSeconds(60), END), false), 400, "VALIDATION_ERROR");
    Map<String, Object> reasonless = body("EXECUTED", START, END);
    reasonless.put("earlyStartReason", "Not early");
    assertError(send(administrator(), KEY, reasonless, false), 400, "VALIDATION_ERROR");
    assertError(send(administrator(), KEY, body("NOT_CARRIED_OUT", START, END), false), 400, "VALIDATION_ERROR");
    Map<String, Object> unknown = body("EXECUTED", START, END);
    unknown.put("who", "me");
    assertError(send(administrator(), KEY, unknown, false), 400, "VALIDATION_ERROR");
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_hours_out_of_order_or_in_the_future_are_422_business_rule() {
    assertError(send(administrator(), KEY, body("EXECUTED", START, START.minusSeconds(1)), true), 422, "BUSINESS_RULE_VIOLATION");
    assertError(send(administrator(), KEY, body("EXECUTED", START, NOW.plusSeconds(120)), true), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(world.departures.get(new UUID(0, 1001)).status()).isEqualTo(DepartureStatus.SCHEDULED);
  }

  @Test
  void C_a_departure_not_marked_or_already_closed_is_422_invalid_status_transition() {
    put(2, TENANT, false);
    Response unmarked = as(administrator()).filter(Contract.filter()).header("Idempotency-Key", KEY).body(body("EXECUTED", START, END))
        .post(DEPARTURES + "/SAL-002/regularization");
    assertError(unmarked, 422, "INVALID_STATUS_TRANSITION");
    send(administrator(), KEY, body("EXECUTED", START, END), true);
    assertError(send(administrator(), "another-key-0002", body("EXECUTED", START, END), true), 422, "INVALID_STATUS_TRANSITION");
  }

  @Test
  void C_without_the_permission_it_is_403_a_customer_too_and_without_a_token_401() {
    assertError(send(token("OPERATIONAL_COLLABORATOR", TENANT), KEY, body("EXECUTED", START, END), true), 403, "FORBIDDEN");
    assertError(send(token("FINAL_CUSTOMER", TENANT, PERMISSION), KEY, body("EXECUTED", START, END), true), 403, "FORBIDDEN");
    assertError(send(null, KEY, body("EXECUTED", START, END), true), 401, "UNAUTHORIZED");
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_a_departure_of_another_tenant_or_that_does_not_exist_is_404() {
    put(3, OTHER_TENANT, true);
    Response other = as(administrator()).filter(Contract.filter()).header("Idempotency-Key", KEY).body(body("EXECUTED", START, END))
        .post(DEPARTURES + "/SAL-003/regularization");
    assertError(other, 404, "NOT_FOUND");
    Response missing = as(administrator()).filter(Contract.filter()).header("Idempotency-Key", KEY).body(body("EXECUTED", START, END))
        .post(DEPARTURES + "/SAL-099/regularization");
    assertError(missing, 404, "NOT_FOUND");
  }

  @Test
  void C_with_the_reservations_down_it_is_503_and_nothing_is_written() {
    world.reservationsDown = true;
    assertError(send(administrator(), KEY, body("EXECUTED", START, END), true), 503, "SERVICE_UNAVAILABLE");
    assertThat(world.events).isEmpty();
    assertThat(world.departures.get(new UUID(0, 1001)).status()).isEqualTo(DepartureStatus.SCHEDULED);
  }
}
