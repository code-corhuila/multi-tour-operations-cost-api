package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Reservations;
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

/** PUT /departures/{departureCode}/schedule: every scenario that can pass or fail, each answer validated against the contract. */
@Import(InMemoryWorld.Config.class)
class RescheduleDepartureRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/operations-cost/departures/SAL-001/schedule";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final Instant START = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final Instant NEW_START = START.plus(Duration.ofDays(1));
  private static final Instant NEW_END = END.plus(Duration.ofDays(1));

  private final InMemoryWorld world = InMemoryWorld.WORLD;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 12));
    seed(DepartureStatus.SCHEDULED);
    world.seats.put(ID, new Reservations.Seats(12, 0));
  }

  private void seed(DepartureStatus status) {
    world.departures.put(ID, new Departure(ID, TENANT, "SAL-001", TOUR, START, END, "America/Bogota", status, ACCOUNT, Instant.now()));
  }

  private static String token(String role, UUID tenant) {
    return Tokens.valid(Tokens.claims(ACCOUNT.toString(), 600, List.of(role), tenant == null ? null : tenant.toString()));
  }

  private static String admin() {
    return token("TENANT_ADMINISTRATOR", TENANT);
  }

  private static Map<String, Object> body() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("scheduledStart", NEW_START.toString());
    body.put("scheduledEnd", NEW_END.toString());
    return body;
  }

  private static RequestSpecification as(String bearer) {
    RequestSpecification request = given().contentType(ContentType.JSON);
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return request;
  }

  private void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("message")).isNotBlank();
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
    assertThat(world.events).isEmpty();
    assertThat(world.departures.get(ID).start()).as("nothing was changed").isEqualTo(START);
  }

  @Test
  void C_changes_the_dates_with_200_keeps_the_code_and_leaves_the_event() {
    Response response = as(admin()).filter(Contract.filter()).body(body()).put(ROUTE);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("code")).isEqualTo("SAL-001");
    assertThat(response.jsonPath().getString("status")).isEqualTo("SCHEDULED");
    assertThat(Instant.parse(response.jsonPath().getString("scheduledStart"))).isEqualTo(NEW_START);
    assertThat(Instant.parse(response.jsonPath().getString("scheduledEnd"))).isEqualTo(NEW_END);
    assertThat(response.jsonPath().getInt("capacity")).isEqualTo(12);
    assertThat(world.departures.get(ID).start()).isEqualTo(NEW_START);
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaRescheduled");
      assertThat(event.correlationId()).isEqualTo(response.header("X-Correlation-Id"));
    });
  }

  @Test
  void C_the_same_values_answer_200_and_record_nothing() {
    Map<String, Object> same = new LinkedHashMap<>();
    same.put("scheduledStart", START.toString());
    same.put("scheduledEnd", END.toString());
    world.seats.put(ID, new Reservations.Seats(12, 4));
    Response response = as(admin()).filter(Contract.filter()).body(same).put(ROUTE);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_no_token_is_401() {
    assertError(as(null).filter(Contract.answers()).body(body()).put(ROUTE), 401, "UNAUTHORIZED");
  }

  @Test
  void C_only_tenant_administrator_and_before_the_body_is_read() {
    for (String role : List.of("OPERATIONAL_COLLABORATOR", "MANAGER", "ACCOUNTANT", "ANALYST", "FINAL_CUSTOMER")) {
      assertError(as(token(role, TENANT)).filter(Contract.answers()).body(body()).put(ROUTE), 403, "FORBIDDEN");
    }
    assertError(as(token("PLATFORM_ADMINISTRATOR", null)).filter(Contract.answers()).body(body()).put(ROUTE), 403, "FORBIDDEN");
    assertError(as(token("MANAGER", TENANT)).filter(Contract.answers()).body("{not json").put(ROUTE), 403, "FORBIDDEN");
  }

  @Test
  void C_another_tenants_departure_and_an_unknown_one_are_404() {
    UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
    assertError(as(token("TENANT_ADMINISTRATOR", other)).filter(Contract.answers()).body(body()).put(ROUTE), 404, "NOT_FOUND");
    assertError(as(admin()).filter(Contract.answers()).body(body()).put("/api/v1/operations-cost/departures/SAL-099/schedule"), 404, "NOT_FOUND");
  }

  @Test
  void C_a_code_that_is_not_a_departure_code_is_400() {
    assertError(as(admin()).filter(Contract.answers()).body(body()).put("/api/v1/operations-cost/departures/NOPE/schedule"), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_a_missing_or_wrong_field_is_400_and_names_it() {
    for (String field : List.of("scheduledStart", "scheduledEnd")) {
      Map<String, Object> body = body();
      body.remove(field);
      Response response = as(admin()).filter(Contract.answers()).body(body).put(ROUTE);
      assertError(response, 400, "VALIDATION_ERROR");
      assertThat(response.jsonPath().getString("details[0].field")).as(field).isEqualTo(field);
    }
    Map<String, Object> endBeforeStart = body();
    endBeforeStart.put("scheduledEnd", NEW_START.minusSeconds(1).toString());
    assertError(as(admin()).filter(Contract.answers()).body(endBeforeStart).put(ROUTE), 400, "VALIDATION_ERROR");
    Map<String, Object> badDate = body();
    badDate.put("scheduledStart", "tomorrow");
    assertError(as(admin()).filter(Contract.answers()).body(badDate).put(ROUTE), 400, "VALIDATION_ERROR");
    Map<String, Object> undeclared = body();
    undeclared.put("timeZone", "America/Bogota");
    assertError(as(admin()).filter(Contract.answers()).body(undeclared).put(ROUTE), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_reservations_make_it_422_and_nothing_changes() {
    world.seats.put(ID, new Reservations.Seats(12, 2));
    assertError(as(admin()).filter(Contract.answers()).body(body()).put(ROUTE), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test
  void C_a_start_in_the_past_and_a_departure_that_cannot_be_proved_empty_are_422() {
    Map<String, Object> past = body();
    past.put("scheduledStart", Instant.now().minus(Duration.ofDays(1)).toString());
    past.put("scheduledEnd", Instant.now().plus(Duration.ofDays(1)).toString());
    assertError(as(admin()).filter(Contract.answers()).body(past).put(ROUTE), 422, "BUSINESS_RULE_VIOLATION");
    world.seats.clear();
    assertError(as(admin()).filter(Contract.answers()).body(body()).put(ROUTE), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test
  void C_a_departure_that_is_not_scheduled_is_422_invalid_status_transition() {
    for (DepartureStatus status : new DepartureStatus[] {DepartureStatus.IN_EXECUTION, DepartureStatus.FINISHED, DepartureStatus.CANCELLED}) {
      seed(status);
      Response response = as(admin()).filter(Contract.answers()).body(body()).put(ROUTE);
      assertThat(response.statusCode()).as(status.name()).isEqualTo(422);
      assertThat(response.jsonPath().getString("error")).isEqualTo("INVALID_STATUS_TRANSITION");
    }
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_reservations_down_is_503_and_nothing_changes() {
    world.reservationsDown = true;
    assertError(as(admin()).filter(Contract.answers()).body(body()).put(ROUTE), 503, "SERVICE_UNAVAILABLE");
  }

  @Test
  void C_the_media_type_is_checked() {
    Response text = given().header("Authorization", "Bearer " + admin()).contentType("text/plain").body("x").put(ROUTE);
    assertThat(text.statusCode()).isEqualTo(415);
  }
}
