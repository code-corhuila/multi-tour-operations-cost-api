package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

/** The internal route that the saga {@code cancel-departure} calls first (HU-OPS-006, api spec 010): SERVICE only, idempotent by state. */
@Import(InMemoryWorld.Config.class)
class CancelDepartureRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/operations-cost/internal/tenants/{tenant}/departures/{code}/cancellation";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final Instant START = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final String KEY = "saga-cancel-0001";

  private final InMemoryWorld world = InMemoryWorld.WORLD;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    put(1, DepartureStatus.SCHEDULED, TENANT, false);
  }

  /** A marked departure is one whose estimated end passed, so it is dated in the past. */
  private void put(int n, DepartureStatus status, UUID tenant, boolean marked) {
    Instant start = marked ? Instant.now().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS) : START;
    Departure created = new Departure(new UUID(0, 1000 + n), tenant, String.format("SAL-%03d", n), TOUR, start,
        marked ? start.plus(Duration.ofHours(8)) : END, "America/Bogota", status, ACCOUNT, Instant.now().minus(Duration.ofDays(5)));
    world.departures.put(created.id(), marked ? created.markPending(Instant.now()) : created);
  }

  private static String service() {
    return Tokens.valid(Tokens.claims("workflow-cancel-departure", 600, List.of("SERVICE"), null));
  }

  private static String administrator() {
    return Tokens.valid(Tokens.claims(ACCOUNT.toString(), 600, List.of("TENANT_ADMINISTRATOR"), TENANT.toString()));
  }

  private static RequestSpecification as(String bearer) {
    RequestSpecification request = given().contentType(ContentType.JSON);
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return request;
  }

  private static Map<String, Object> body() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cause", "NOT_ATTRIBUTABLE_TO_CUSTOMER");
    return body;
  }

  private static Response send(String bearer, UUID tenant, String code, String key, Map<String, Object> body, boolean validRequest) {
    RequestSpecification request = as(bearer).filter(validRequest ? Contract.filter() : Contract.answers()).body(body);
    return (key == null ? request : request.header("Idempotency-Key", key)).post(ROUTE, tenant, code);
  }

  private static void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_the_service_cancels_a_scheduled_departure_and_the_event_is_written() {
    Response response = send(service(), TENANT, "SAL-001", KEY, body(), true);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("departureId")).isEqualTo(new UUID(0, 1001).toString());
    assertThat(Instant.parse(response.jsonPath().getString("cancelledAt"))).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    assertThat(world.departures.get(new UUID(0, 1001)).status()).isEqualTo(DepartureStatus.CANCELLED);
    assertThat(world.events).extracting(e -> e.eventType()).containsExactly("SalidaCancelled");
  }

  @Test
  void C_asked_again_with_any_key_it_answers_the_original_instant_and_writes_nothing_more() {
    String first = send(service(), TENANT, "SAL-001", KEY, body(), true).jsonPath().getString("cancelledAt");
    Response again = send(service(), TENANT, "SAL-001", "another-key-0002", body(), true);
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getString("cancelledAt")).isEqualTo(first);
    assertThat(world.events).hasSize(1);
  }

  @Test
  void C_a_key_already_used_for_another_departure_is_422_business_rule_and_writes_nothing_more() {
    put(2, DepartureStatus.SCHEDULED, TENANT, false);
    send(service(), TENANT, "SAL-001", KEY, body(), true);
    assertError(send(service(), TENANT, "SAL-002", KEY, body(), true), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(world.departures.get(new UUID(0, 1002)).status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(world.events).hasSize(1);
  }

  @Test
  void C_a_departure_in_execution_or_finished_is_422_invalid_status_transition() {
    put(2, DepartureStatus.IN_EXECUTION, TENANT, false);
    put(3, DepartureStatus.FINISHED, TENANT, false);
    assertError(send(service(), TENANT, "SAL-002", KEY, body(), true), 422, "INVALID_STATUS_TRANSITION");
    assertError(send(service(), TENANT, "SAL-003", KEY, body(), true), 422, "INVALID_STATUS_TRANSITION");
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_a_collaborator_may_ask_only_for_a_marked_departure() {
    put(2, DepartureStatus.SCHEDULED, TENANT, true);
    Map<String, Object> collaborator = body();
    collaborator.put("requestedByRole", "OPERATIONAL_COLLABORATOR");
    collaborator.put("requestedBy", ACCOUNT.toString());
    assertError(send(service(), TENANT, "SAL-001", KEY, collaborator, true), 403, "FORBIDDEN");
    assertThat(world.events).isEmpty();
    assertThat(send(service(), TENANT, "SAL-002", "saga-cancel-0002", collaborator, true).statusCode()).isEqualTo(200);
  }

  @Test
  void C_a_request_that_is_not_valid_is_400() {
    assertError(send(service(), TENANT, "SAL-001", null, body(), false), 400, "VALIDATION_ERROR");
    assertError(send(service(), TENANT, "SAL-001", "short", body(), false), 400, "VALIDATION_ERROR");
    Map<String, Object> otherCause = body();
    otherCause.put("cause", "NOT_CARRIED_OUT");
    assertError(send(service(), TENANT, "SAL-001", KEY, otherCause, false), 400, "VALIDATION_ERROR");
    assertError(send(service(), TENANT, "SAL-001", KEY, new LinkedHashMap<>(), false), 400, "VALIDATION_ERROR");
    Map<String, Object> unknownRole = body();
    unknownRole.put("requestedByRole", "FINAL_CUSTOMER");
    assertError(send(service(), TENANT, "SAL-001", KEY, unknownRole, false), 400, "VALIDATION_ERROR");
    Map<String, Object> unknown = body();
    unknown.put("who", "me");
    assertError(send(service(), TENANT, "SAL-001", KEY, unknown, false), 400, "VALIDATION_ERROR");
    assertError(send(service(), TENANT, "SAL-1", KEY, body(), false), 400, "VALIDATION_ERROR");
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_a_departure_of_another_tenant_or_that_does_not_exist_is_404() {
    assertError(send(service(), OTHER_TENANT, "SAL-001", KEY, body(), true), 404, "NOT_FOUND");
    assertError(send(service(), TENANT, "SAL-099", KEY, body(), true), 404, "NOT_FOUND");
    assertThat(world.departures.get(new UUID(0, 1001)).status()).isEqualTo(DepartureStatus.SCHEDULED);
  }

  @Test
  void C_only_the_service_role_opens_it_a_person_is_403_and_without_a_token_401() {
    assertError(send(administrator(), TENANT, "SAL-001", KEY, body(), true), 403, "FORBIDDEN");
    assertError(send(null, TENANT, "SAL-001", KEY, body(), true), 401, "UNAUTHORIZED");
    assertThat(world.events).isEmpty();
  }
}
