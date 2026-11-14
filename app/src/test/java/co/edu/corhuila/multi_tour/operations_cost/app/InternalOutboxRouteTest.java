package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.outbox.StoredEvent;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** The three internal routes of the outbox that job 7 reads: only the SERVICE role, every answer validated against the contract. */
@Import(InMemoryWorld.Config.class)
class InternalOutboxRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/operations-cost/internal/outbox";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");

  private final InMemoryOutboxEvents store = InMemoryOutboxEvents.STORE;

  @BeforeEach
  void setUp() {
    store.events.clear();
    for (int i = 1; i <= 3; i++) {
      store.events.add(event(i, Instant.now().minusSeconds(100 - i)));
    }
  }

  private static StoredEvent event(int n, Instant occurredAt) {
    return new StoredEvent(new UUID(0, n), "SalidaScheduled", 1, TENANT, new UUID(2, n), ACCOUNT, null,
        Map.of("salidaId", new UUID(2, n).toString(), "tenantId", TENANT.toString()), "trace-" + n, occurredAt, null,
        StoredEvent.Status.PENDING, 0, null, occurredAt);
  }

  private static String service() {
    return Tokens.valid(Tokens.claims("event-relay", 600, List.of("SERVICE"), null));
  }

  private static String person(String role) {
    return Tokens.valid(Tokens.claims(ACCOUNT.toString(), 600, List.of(role), role.equals("PLATFORM_ADMINISTRATOR") ? null : TENANT.toString()));
  }

  private static RequestSpecification as(String bearer) {
    RequestSpecification request = given().contentType(ContentType.JSON);
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return request;
  }

  private static void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_the_events_waiting_are_listed_oldest_first_with_the_envelope() {
    Response response = as(service()).filter(Contract.filter()).get(ROUTE + "?status=PENDING&page=1&limit=2");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("data.eventId")).containsExactly(new UUID(0, 1).toString(), new UUID(0, 2).toString());
    assertThat(response.jsonPath().getInt("meta.total")).isEqualTo(3);
    assertThat(response.jsonPath().getInt("meta.totalPages")).isEqualTo(2);
    assertThat(response.jsonPath().getString("data[0].eventType")).isEqualTo("SalidaScheduled");
    assertThat(response.jsonPath().getString("data[0].tenantId")).isEqualTo(TENANT.toString());
    assertThat(response.jsonPath().getString("data[0].status")).isEqualTo("PENDING");
    assertThat(response.jsonPath().getString("data[0].payload.salidaId")).isEqualTo(new UUID(2, 1).toString());
  }

  @Test
  void C_the_defaults_are_page_1_limit_20_status_pending_and_an_empty_listing_is_200() {
    assertThat(as(service()).filter(Contract.filter()).get(ROUTE).jsonPath().getInt("meta.limit")).isEqualTo(20);
    store.events.clear();
    Response empty = as(service()).filter(Contract.filter()).get(ROUTE);
    assertThat(empty.statusCode()).isEqualTo(200);
    assertThat(empty.jsonPath().getList("data")).isEmpty();
    assertThat(empty.jsonPath().getInt("meta.totalPages")).isZero();
  }

  @Test
  void C_a_page_a_limit_a_status_or_a_parameter_that_is_not_valid_is_400_and_not_trimmed() {
    for (String query : List.of("?page=0", "?limit=0", "?limit=101", "?page=x", "?status=FAILED", "?status=", "?owner=me")) {
      assertError(as(service()).filter(Contract.answers()).get(ROUTE + query), 400, "VALIDATION_ERROR");
    }
  }

  @Test
  void C_no_token_is_401_and_only_the_service_role_opens_the_internal_routes() {
    assertError(as(null).filter(Contract.answers()).get(ROUTE), 401, "UNAUTHORIZED");
    for (String role : List.of("TENANT_ADMINISTRATOR", "OPERATIONAL_COLLABORATOR", "MANAGER", "ACCOUNTANT", "ANALYST", "FINAL_CUSTOMER",
        "PLATFORM_ADMINISTRATOR")) {
      assertError(as(person(role)).filter(Contract.answers()).get(ROUTE), 403, "FORBIDDEN");
      assertError(as(person(role)).filter(Contract.answers()).post(ROUTE + "/" + new UUID(0, 1) + "/published"), 403, "FORBIDDEN");
      assertError(as(person(role)).filter(Contract.answers()).body("{\"error\":\"x\"}").post(ROUTE + "/" + new UUID(0, 1) + "/attempts"), 403, "FORBIDDEN");
    }
  }

  @Test
  void C_the_service_role_opens_nothing_else() {
    Response response = as(service()).filter(Contract.answers()).header("Idempotency-Key", "key-0001-abcdef").body("{}")
        .post("/api/v1/operations-cost/departures");
    assertError(response, 403, "FORBIDDEN");
  }

  @Test
  void C_an_event_delivered_is_marked_published_and_leaves_the_listing() {
    Response response = as(service()).filter(Contract.filter()).post(ROUTE + "/" + new UUID(0, 1) + "/published");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("status")).isEqualTo("PUBLISHED");
    assertThat(response.jsonPath().getString("publishedAt")).isNotBlank();
    assertThat(as(service()).get(ROUTE).jsonPath().getList("data.eventId")).doesNotContain(new UUID(0, 1).toString());
    assertThat(as(service()).filter(Contract.filter()).post(ROUTE + "/" + new UUID(0, 1) + "/published").statusCode()).isEqualTo(200);
  }

  @Test
  void C_an_event_that_does_not_exist_is_404_and_an_id_that_is_not_a_uuid_is_400() {
    assertError(as(service()).filter(Contract.answers()).post(ROUTE + "/" + new UUID(9, 9) + "/published"), 404, "NOT_FOUND");
    assertError(as(service()).filter(Contract.answers()).body("{\"error\":\"x\"}").post(ROUTE + "/" + new UUID(9, 9) + "/attempts"), 404, "NOT_FOUND");
    assertError(as(service()).filter(Contract.answers()).post(ROUTE + "/not-a-uuid/published"), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).body("{\"error\":\"x\"}").post(ROUTE + "/not-a-uuid/attempts"), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_a_failed_delivery_counts_keeps_the_error_and_moves_the_next_attempt() {
    Response response = as(service()).filter(Contract.filter()).body("{\"error\":\"consumer answered 503\"}").post(ROUTE + "/" + new UUID(0, 1) + "/attempts");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getInt("attempts")).isEqualTo(1);
    assertThat(response.jsonPath().getString("lastError")).isEqualTo("consumer answered 503");
    assertThat(response.jsonPath().getString("status")).isEqualTo("PENDING");
    assertThat(Instant.parse(response.jsonPath().getString("nextAttemptAt"))).isAfter(Instant.now());
    assertThat(as(service()).get(ROUTE).jsonPath().getList("data.eventId")).as("it waits for its next attempt")
        .doesNotContain(new UUID(0, 1).toString());
  }

  @Test
  void C_a_report_without_an_error_or_with_one_that_is_not_valid_is_400() {
    String route = ROUTE + "/" + new UUID(0, 1) + "/attempts";
    for (String body : List.of("{}", "{\"error\":\"\"}", "{\"error\":\"   \"}", "{\"error\":\"" + "x".repeat(1001) + "\"}",
        "{\"error\":\"line\\nbreak\"}", "{\"error\":\"x\",\"retry\":true}", "{not json")) {
      assertError(as(service()).filter(Contract.answers()).body(body).post(route), 400, "VALIDATION_ERROR");
    }
    assertThat(as(service()).header("Content-Type", "text/plain").body("x").post(route).statusCode()).isEqualTo(415);
  }

  @Test
  void C_a_late_report_on_an_event_already_published_changes_nothing() {
    as(service()).post(ROUTE + "/" + new UUID(0, 1) + "/published");
    Response late = as(service()).filter(Contract.filter()).body("{\"error\":\"late\"}").post(ROUTE + "/" + new UUID(0, 1) + "/attempts");
    assertThat(late.statusCode()).isEqualTo(200);
    assertThat(late.jsonPath().getString("status")).isEqualTo("PUBLISHED");
    assertThat(late.jsonPath().getInt("attempts")).isZero();
  }

  @Test
  void C_a_path_written_in_an_odd_way_never_opens_an_internal_route() {
    for (String path : List.of("/api/v1/operations-cost/internal/outbox/", "/api/v1/operations-cost//internal/outbox",
        "/api/v1/operations-cost/internal/%2e%2e/internal/outbox", "/api/v1/operations-cost/internal/outbox;x=1",
        "/api/v1/operations-cost/INTERNAL/outbox", "/api/v1/operations-cost/internal-x/outbox")) {
      // the client of the test collapses a doubled slash before sending it, so for the service that path is the plain one
      List<String> tokens = path.contains("//") ? List.of(person("TENANT_ADMINISTRATOR")) : List.of(service(), person("TENANT_ADMINISTRATOR"));
      for (String bearer : tokens) {
        Response response = given().urlEncodingEnabled(false).header("Authorization", "Bearer " + bearer).get(path);
        assertThat(response.statusCode()).as(path).isIn(400, 403, 404);
      }
    }
  }
}
