package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
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

/** POST /departures: every scenario that can pass or fail, each answer validated against the contract (RN-OPS-API-10 to -18). */
@Import(InMemoryWorld.Config.class)
class ScheduleDepartureRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/operations-cost/departures";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final Instant START = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);

  private final InMemoryWorld world = InMemoryWorld.WORLD;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 12));
  }

  private static String token(String role, UUID tenant, String subject) {
    return Tokens.valid(Tokens.claims(subject, 600, List.of(role), tenant == null ? null : tenant.toString()));
  }

  private static String admin() {
    return token("TENANT_ADMINISTRATOR", TENANT, ACCOUNT.toString());
  }

  private static Map<String, Object> body() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("serviceId", TOUR.toString());
    body.put("scheduledStart", START.toString());
    body.put("scheduledEnd", START.plus(Duration.ofHours(8)).toString());
    body.put("timeZone", "America/Bogota");
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
    assertThat(world.departures).isEmpty();
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_schedules_a_departure_with_201_the_location_and_the_capacity_of_the_tour() {
    Response response = as(admin(), "key-0001-abcdef").filter(Contract.filter()).body(body()).post(ROUTE);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.header("Location")).isEqualTo(ROUTE + "/SAL-001");
    assertThat(response.jsonPath().getString("code")).isEqualTo("SAL-001");
    assertThat(response.jsonPath().getString("status")).isEqualTo("SCHEDULED");
    assertThat(response.jsonPath().getInt("capacity")).isEqualTo(12);
    assertThat(response.jsonPath().getInt("booked")).isZero();
    assertThat(response.jsonPath().getList("vehicles")).isEmpty();
    assertThat(Instant.parse(response.jsonPath().getString("scheduledStart"))).isEqualTo(START);
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaScheduled");
      assertThat(event.correlationId()).isEqualTo(response.header("X-Correlation-Id"));
    });
  }

  @Test
  void C_create_repeat_returns_the_same_departure_with_200() {
    Response first = as(admin(), "key-0001-abcdef").filter(Contract.filter()).body(body()).post(ROUTE);
    Response repeat = as(admin(), "key-0001-abcdef").filter(Contract.filter()).body(body()).post(ROUTE);
    assertThat(repeat.statusCode()).isEqualTo(200);
    assertThat(repeat.jsonPath().getString("code")).isEqualTo(first.jsonPath().getString("code"));
    assertThat(world.departures).hasSize(1);
    assertThat(world.events).hasSize(1);
  }

  @Test
  void C_a_correlation_id_sent_is_the_trace_id_of_the_event() {
    Response response = as(admin(), "key-0001-abcdef").header("X-Correlation-Id", "trace-from-the-caller").body(body()).post(ROUTE);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(world.events.get(0).correlationId()).isEqualTo("trace-from-the-caller");
  }

  @Test
  void C_no_token_is_401() {
    assertError(as(null, "key-0001-abcdef").filter(Contract.answers()).body(body()).post(ROUTE), 401, "UNAUTHORIZED");
  }

  @Test
  void C_only_tenant_administrator() {
    for (String role : List.of("OPERATIONAL_COLLABORATOR", "MANAGER", "ACCOUNTANT", "ANALYST", "FINAL_CUSTOMER")) {
      assertError(as(token(role, TENANT, ACCOUNT.toString()), "key-0001-abcdef").filter(Contract.answers()).body(body()).post(ROUTE),
          403, "FORBIDDEN");
    }
    assertError(as(token("PLATFORM_ADMINISTRATOR", null, ACCOUNT.toString()), "key-0001-abcdef").filter(Contract.answers())
        .body(body()).post(ROUTE), 403, "FORBIDDEN");
    assertError(as(token("SERVICE", null, "event-relay"), "key-0001-abcdef").filter(Contract.answers()).body(body()).post(ROUTE),
        403, "FORBIDDEN");
  }

  @Test
  void C_the_role_is_judged_before_the_body_is_read() {
    assertError(as(token("MANAGER", TENANT, ACCOUNT.toString()), "key-0001-abcdef").filter(Contract.answers()).body("{not json").post(ROUTE),
        403, "FORBIDDEN");
  }

  @Test
  void C_a_subject_that_is_not_a_uuid_is_403() {
    assertError(as(token("TENANT_ADMINISTRATOR", TENANT, "account-1"), "key-0001-abcdef").filter(Contract.answers()).body(body()).post(ROUTE),
        403, "FORBIDDEN");
  }

  @Test
  void C_a_missing_or_wrong_idempotency_key_is_400() {
    for (String key : new String[] {null, "short-7", "k".repeat(129)}) {
      Response response = as(admin(), key).filter(Contract.answers()).body(body()).post(ROUTE);
      assertError(response, 400, "VALIDATION_ERROR");
      assertThat(response.jsonPath().getString("details[0].field")).isEqualTo("Idempotency-Key");
    }
  }

  @Test
  void C_a_missing_field_is_400_and_names_it() {
    for (String field : List.of("serviceId", "scheduledStart", "scheduledEnd", "timeZone")) {
      Map<String, Object> body = body();
      body.remove(field);
      Response response = as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(body).post(ROUTE);
      assertError(response, 400, "VALIDATION_ERROR");
      assertThat(response.jsonPath().getString("details[0].field")).as(field).isEqualTo(field);
    }
  }

  @Test
  void C_a_wrong_value_is_400() {
    Map<String, Object> endBeforeStart = body();
    endBeforeStart.put("scheduledEnd", START.minusSeconds(1).toString());
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(endBeforeStart).post(ROUTE), 400, "VALIDATION_ERROR");
    Map<String, Object> badZone = body();
    badZone.put("timeZone", "Mars/Olympus");
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(badZone).post(ROUTE), 400, "VALIDATION_ERROR");
    Map<String, Object> badTour = body();
    badTour.put("serviceId", "not-a-uuid");
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(badTour).post(ROUTE), 400, "VALIDATION_ERROR");
    Map<String, Object> badDate = body();
    badDate.put("scheduledStart", "tomorrow");
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(badDate).post(ROUTE), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_an_undeclared_field_is_400_and_the_tenant_cannot_be_chosen() {
    Map<String, Object> withTenant = body();
    withTenant.put("tenantId", "00000000-0000-4000-8000-0000000000bb");
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(withTenant).post(ROUTE), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_a_tour_that_is_not_there_or_not_a_tour_or_not_active_is_422() {
    Map<String, Object> unknown = body();
    unknown.put("serviceId", "00000000-0000-4000-8000-0000000000ee");
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(unknown).post(ROUTE), 422, "BUSINESS_RULE_VIOLATION");
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TRANSPORTATION, true, 40));
    assertError(as(admin(), "key-0002-abcdef").filter(Contract.answers()).body(body()).post(ROUTE), 422, "BUSINESS_RULE_VIOLATION");
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, false, 12));
    assertError(as(admin(), "key-0003-abcdef").filter(Contract.answers()).body(body()).post(ROUTE), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test
  void C_a_start_in_the_past_is_422() {
    Map<String, Object> past = body();
    past.put("scheduledStart", Instant.now().minus(Duration.ofDays(1)).toString());
    past.put("scheduledEnd", Instant.now().plus(Duration.ofDays(1)).toString());
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(past).post(ROUTE), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test
  void C_catalog_down_is_503_and_nothing_is_written() {
    world.catalogDown = true;
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(body()).post(ROUTE), 503, "SERVICE_UNAVAILABLE");
  }

  @Test
  void C_the_same_key_in_another_tenant_is_another_departure() {
    UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
    as(admin(), "key-0001-abcdef").body(body()).post(ROUTE);
    Response second = as(token("TENANT_ADMINISTRATOR", other, ACCOUNT.toString()), "key-0001-abcdef").body(body()).post(ROUTE);
    assertThat(second.statusCode()).isEqualTo(201);
    assertThat(world.departures).hasSize(2);
  }

  @Test
  void C_a_wrong_method_or_media_type_is_an_error_of_the_envelope() {
    Response get = given().header("Authorization", "Bearer " + admin()).get(ROUTE);
    assertThat(get.statusCode()).isEqualTo(405);
    Response text = given().header("Authorization", "Bearer " + admin()).header("Idempotency-Key", "key-0001-abcdef")
        .contentType("text/plain").body("x").post(ROUTE);
    assertThat(text.statusCode()).isEqualTo(415);
  }

  @Test
  void C_a_bad_request_is_400_or_422_without_asking_the_catalog_even_when_it_is_down() {
    world.catalogDown = true;
    Map<String, Object> badZone = body();
    badZone.put("timeZone", "Mars/Olympus");
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(badZone).post(ROUTE), 400, "VALIDATION_ERROR");
    Map<String, Object> endBeforeStart = body();
    endBeforeStart.put("scheduledEnd", START.minusSeconds(1).toString());
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(endBeforeStart).post(ROUTE), 400, "VALIDATION_ERROR");
    Map<String, Object> past = body();
    past.put("scheduledStart", Instant.now().minus(Duration.ofDays(1)).toString());
    past.put("scheduledEnd", Instant.now().plus(Duration.ofDays(1)).toString());
    assertError(as(admin(), "key-0001-abcdef").filter(Contract.answers()).body(past).post(ROUTE), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(world.catalogCalls).isZero();
  }

  @Test
  void C_a_repeat_answers_200_with_capacity_zero_when_the_catalog_is_down() {
    as(admin(), "key-0001-abcdef").body(body()).post(ROUTE);
    world.catalogDown = true;
    Response repeat = as(admin(), "key-0001-abcdef").filter(Contract.filter()).body(body()).post(ROUTE);
    assertThat(repeat.statusCode()).isEqualTo(200);
    assertThat(repeat.jsonPath().getInt("capacity")).isZero();
  }

  @Test
  void C_a_key_with_white_space_is_400() {
    assertError(as(admin(), "a key with spaces").filter(Contract.answers()).body(body()).post(ROUTE), 400, "VALIDATION_ERROR");
  }
}
