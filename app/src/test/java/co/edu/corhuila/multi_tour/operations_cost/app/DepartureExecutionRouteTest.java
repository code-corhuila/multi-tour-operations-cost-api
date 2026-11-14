package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Reservations;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * The four routes of HU-OPS-003 part A, each answer validated against the contract (api spec 004, AC-API-04.1 to -04.13): list and
 * read the departures, record the start, record the end or the interruption. Who may call is decided by the permission of the token.
 */
@Import(InMemoryWorld.Config.class)
class DepartureExecutionRouteTest extends ShellTest {

  private static final String DEPARTURES = "/api/v1/operations-cost/departures";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID COLLABORATOR = UUID.fromString("00000000-0000-4000-8000-0000000000dd");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private static final Instant START = NOW.minus(Duration.ofHours(2));
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final String PERMISSION = "MANAGE_DEPARTURE_EXECUTION";

  private final InMemoryWorld world = InMemoryWorld.WORLD;
  private Departure departure;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    departure = departure(1, START, DepartureStatus.SCHEDULED, TENANT);
  }

  private Departure departure(int n, Instant start, DepartureStatus status, UUID tenant) {
    Departure created = new Departure(new UUID(0, 1000 + n), tenant, String.format("SAL-%03d", n), TOUR, start, start.plus(Duration.ofHours(8)),
        "America/Bogota", status, ACCOUNT, start.minus(Duration.ofDays(3)));
    world.departures.put(created.id(), created);
    return created;
  }

  private static String token(String role, UUID tenant, UUID subject, String... permissions) {
    var claims = Tokens.claims(subject.toString(), 600, List.of(role), tenant.toString());
    return Tokens.valid(permissions.length == 0 ? claims : claims.claim("permissions", List.of(permissions)));
  }

  private static String administrator() {
    return token("TENANT_ADMINISTRATOR", TENANT, ACCOUNT, PERMISSION);
  }

  private static String collaborator() {
    return token("OPERATIONAL_COLLABORATOR", TENANT, COLLABORATOR, PERMISSION);
  }

  private static RequestSpecification as(String bearer) {
    RequestSpecification request = given().contentType(ContentType.JSON);
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return request;
  }

  private static Map<String, Object> start(Instant at) {
    return Map.of("startedAt", at.toString());
  }

  private static Map<String, Object> finish(Instant at, String result, String cause) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("finishedAt", at.toString());
    body.put("result", result);
    if (cause != null) {
      body.put("cause", cause);
    }
    return body;
  }

  private void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("message")).isNotBlank();
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  // ---- the list and the reading

  @Test
  void C_the_list_is_the_tenants_most_recent_first_with_capacity_seats_and_the_mark() {
    Departure newer = departure(2, START.plus(Duration.ofDays(5)), DepartureStatus.SCHEDULED, TENANT);
    departure(3, START.plus(Duration.ofDays(3)), DepartureStatus.SCHEDULED, OTHER_TENANT);
    world.seats.put(newer.id(), new Reservations.Seats(18, 6));
    Response response = as(administrator()).filter(Contract.filter()).get(DEPARTURES);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("data.code")).containsExactly("SAL-002", "SAL-001");
    assertThat(response.jsonPath().getInt("data[0].booked")).isEqualTo(6);
    assertThat(response.jsonPath().getInt("data[0].capacity")).isZero();
    assertThat(response.jsonPath().getBoolean("data[0].pendingRegularization")).isFalse();
    assertThat(response.jsonPath().getBoolean("data[0].interrupted")).isFalse();
    assertThat(response.jsonPath().getInt("meta.total")).isEqualTo(2);
    assertThat(response.jsonPath().getInt("meta.totalPages")).isEqualTo(1);
  }

  @Test
  void C_list_and_get_answer_start_alert_only_for_a_scheduled_departure_past_its_start() {
    departure(2, START.plus(Duration.ofDays(5)), DepartureStatus.SCHEDULED, TENANT);
    departure(3, START.minus(Duration.ofDays(1)), DepartureStatus.IN_EXECUTION, TENANT);
    Response list = as(administrator()).filter(Contract.filter()).get(DEPARTURES);
    assertThat(list.jsonPath().getList("data.code")).containsExactly("SAL-002", "SAL-001", "SAL-003");
    assertThat(list.jsonPath().getList("data.startAlert", Boolean.class)).containsExactly(false, true, false);
    assertThat(as(administrator()).filter(Contract.filter()).get(DEPARTURES + "/SAL-001").jsonPath().getBoolean("startAlert")).isTrue();
  }

  @Test
  void C_the_list_pages_and_filters_by_status() {
    departure(2, START.plus(Duration.ofDays(1)), DepartureStatus.IN_EXECUTION, TENANT);
    Response page = as(administrator()).filter(Contract.filter()).queryParam("limit", 1).queryParam("page", 2).get(DEPARTURES);
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.jsonPath().getList("data")).hasSize(1);
    assertThat(page.jsonPath().getInt("meta.totalPages")).isEqualTo(2);
    Response running = as(administrator()).filter(Contract.filter()).queryParam("status", "IN_EXECUTION").get(DEPARTURES);
    assertThat(running.jsonPath().getList("data.code")).containsExactly("SAL-002");
  }

  @Test
  void C_a_limit_a_page_a_status_or_a_parameter_that_is_not_valid_is_400_and_never_ignored() {
    for (String[] bad : new String[][] {{"limit", "0"}, {"limit", "101"}, {"page", "0"}, {"status", "PENDING_REGULARIZATION"}, {"status", "x"}, {"sort", "code"}}) {
      assertError(as(administrator()).filter(Contract.answers()).queryParam(bad[0], bad[1]).get(DEPARTURES), 400, "VALIDATION_ERROR");
    }
  }

  @Test
  void C_the_name_of_an_unknown_parameter_is_not_echoed_back() {
    Response response = as(administrator()).filter(Contract.answers()).queryParam("a-very-odd<name>", "1").get(DEPARTURES);
    assertError(response, 400, "VALIDATION_ERROR");
    assertThat(response.asString()).doesNotContain("a-very-odd");
  }

  @Test
  void C_the_same_key_in_two_tenants_is_two_keys() {
    Departure other = departure(2, START, DepartureStatus.SCHEDULED, OTHER_TENANT);
    String strangers = token("TENANT_ADMINISTRATOR", OTHER_TENANT, ACCOUNT, PERMISSION);
    assertThat(as(administrator()).header("Idempotency-Key", "start-key-0001").body(start(NOW)).post(DEPARTURES + "/{code}/executions", "SAL-001")
        .statusCode()).isEqualTo(201);
    assertThat(as(strangers).header("Idempotency-Key", "start-key-0001").body(start(NOW)).post(DEPARTURES + "/{code}/executions", "SAL-002")
        .statusCode()).isEqualTo(201);
    assertThat(world.departures.get(other.id()).status()).isEqualTo(DepartureStatus.IN_EXECUTION);
  }

  @Test
  void C_the_list_with_reservations_down_is_503() {
    world.reservationsDown = true;
    assertError(as(administrator()).filter(Contract.answers()).get(DEPARTURES), 503, "SERVICE_UNAVAILABLE");
  }

  @Test
  void C_one_departure_is_read_with_its_vehicles_seats_and_real_times() {
    Vehicle van = new Vehicle(new UUID(0, 2001), TENANT, "Van", 12, ACCOUNT, NOW);
    world.vehicleRows.put(van.id(), van);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id())));
    world.seats.put(departure.id(), new Reservations.Seats(12, 5));
    Response response = as(administrator()).filter(Contract.filter()).get(DEPARTURES + "/{code}", "SAL-001");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("code")).isEqualTo("SAL-001");
    assertThat(response.jsonPath().getString("serviceId")).isEqualTo(TOUR.toString());
    assertThat(response.jsonPath().getInt("capacity")).isEqualTo(12);
    assertThat(response.jsonPath().getInt("booked")).isEqualTo(5);
    assertThat(response.jsonPath().getList("vehicles.name")).containsExactly("Van");
    assertThat(response.jsonPath().getString("actualStart")).isNull();
  }

  @Test
  void C_a_departure_of_another_tenant_or_that_does_not_exist_is_404_and_a_bad_code_is_400() {
    departure(2, START, DepartureStatus.SCHEDULED, OTHER_TENANT);
    assertError(as(administrator()).filter(Contract.answers()).get(DEPARTURES + "/{code}", "SAL-002"), 404, "NOT_FOUND");
    assertError(as(administrator()).filter(Contract.answers()).get(DEPARTURES + "/{code}", "SAL-404"), 404, "NOT_FOUND");
    // the validator of the contract refuses this request by itself, so it is sent without it
    assertError(as(administrator()).get(DEPARTURES + "/{code}", "sal-001"), 400, "VALIDATION_ERROR");
  }

  // ---- the start

  @Test
  void C_the_start_is_201_with_its_location_the_real_time_and_the_event() {
    Instant at = NOW.minus(Duration.ofMinutes(30));
    Response response = as(administrator()).filter(Contract.filter()).header("Idempotency-Key", "start-key-0001").body(start(at))
        .post(DEPARTURES + "/{code}/executions", "SAL-001");
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.header("Location")).endsWith(DEPARTURES + "/SAL-001");
    assertThat(response.jsonPath().getString("status")).isEqualTo("IN_EXECUTION");
    assertThat(Instant.parse(response.jsonPath().getString("actualStart"))).isEqualTo(at);
    assertThat(response.jsonPath().getBoolean("interrupted")).isFalse();
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaExecutionStarted");
      assertThat(event.payload()).containsEntry("actualStart", at.toString());
      assertThat(event.correlationId()).isEqualTo(response.header("X-Correlation-Id"));
    });
  }

  @Test
  void C_a_collaborator_with_the_permission_can_start_and_the_same_key_answers_200() {
    Response first = as(collaborator()).filter(Contract.filter()).header("Idempotency-Key", "start-key-0001").body(start(NOW))
        .post(DEPARTURES + "/{code}/executions", "SAL-001");
    assertThat(first.statusCode()).isEqualTo(201);
    Response again = as(collaborator()).filter(Contract.filter()).header("Idempotency-Key", "start-key-0001").body(start(NOW.minusSeconds(5)))
        .post(DEPARTURES + "/{code}/executions", "SAL-001");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getString("actualStart")).isEqualTo(first.jsonPath().getString("actualStart"));
    assertThat(world.events).hasSize(1);
    assertThat(world.departures.get(departure.id()).execution().startedBy()).isEqualTo(COLLABORATOR);
  }

  @Test
  void C_a_start_before_the_schedule_or_in_the_future_is_422_and_nothing_is_written() {
    for (Instant bad : new Instant[] {START.minusSeconds(60), NOW.plus(Duration.ofHours(1))}) {
      assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "start-key-0001").body(start(bad))
          .post(DEPARTURES + "/{code}/executions", "SAL-001"), 422, "BUSINESS_RULE_VIOLATION");
    }
    assertThat(world.events).isEmpty();
    assertThat(world.departures.get(departure.id())).isEqualTo(departure);
  }

  @Test
  void C_a_departure_that_is_not_scheduled_is_422_invalid_status_transition() {
    departure(2, START, DepartureStatus.IN_EXECUTION, TENANT);
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "start-key-0001").body(start(NOW))
        .post(DEPARTURES + "/{code}/executions", "SAL-002"), 422, "INVALID_STATUS_TRANSITION");
  }

  @Test
  void C_a_start_without_its_key_its_time_or_with_an_unknown_field_is_400_and_the_departure_of_another_tenant_is_404() {
    assertError(as(administrator()).filter(Contract.answers()).body(start(NOW)).post(DEPARTURES + "/{code}/executions", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "start-key-0001").body(Map.of())
        .post(DEPARTURES + "/{code}/executions", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "start-key-0001")
        .body(Map.of("startedAt", NOW.toString(), "extra", 1)).post(DEPARTURES + "/{code}/executions", "SAL-001"), 400, "VALIDATION_ERROR");
    departure(2, START, DepartureStatus.SCHEDULED, OTHER_TENANT);
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "start-key-0001").body(start(NOW))
        .post(DEPARTURES + "/{code}/executions", "SAL-002"), 404, "NOT_FOUND");
  }

  @Test
  void C_a_start_with_reservations_down_is_503_and_writes_nothing() {
    world.reservationsDown = true;
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "start-key-0001").body(start(NOW))
        .post(DEPARTURES + "/{code}/executions", "SAL-001"), 503, "SERVICE_UNAVAILABLE");
    assertThat(world.events).isEmpty();
    assertThat(world.departures.get(departure.id())).isEqualTo(departure);
  }

  // ---- the end and the interruption

  private Departure running() {
    Departure started = departure.start(START.plusSeconds(60), COLLABORATOR, NOW);
    world.departures.put(started.id(), started);
    return started;
  }

  @Test
  void C_the_end_is_201_the_departure_is_finished_and_the_event_says_it_was_not_an_interruption() {
    running();
    Instant at = NOW.minus(Duration.ofMinutes(5));
    Response response = as(administrator()).filter(Contract.filter()).header("Idempotency-Key", "finish-key-001").body(finish(at, "FINISHED", null))
        .post(DEPARTURES + "/{code}/finish", "SAL-001");
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.jsonPath().getString("status")).isEqualTo("FINISHED");
    assertThat(Instant.parse(response.jsonPath().getString("actualEnd"))).isEqualTo(at);
    assertThat(response.jsonPath().getBoolean("interrupted")).isFalse();
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaFinalized");
      assertThat(event.payload()).containsEntry("interrupted", false);
    });
  }

  @Test
  void C_an_interruption_ends_the_departure_finished_with_its_cause_and_the_same_key_answers_200() {
    running();
    Response first = as(administrator()).filter(Contract.filter()).header("Idempotency-Key", "finish-key-001")
        .body(finish(NOW, "INTERRUPTED", "Heavy rain closed the trail")).post(DEPARTURES + "/{code}/finish", "SAL-001");
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(first.jsonPath().getString("status")).isEqualTo("FINISHED");
    assertThat(first.jsonPath().getBoolean("interrupted")).isTrue();
    assertThat(first.jsonPath().getString("interruptionCause")).isEqualTo("Heavy rain closed the trail");
    Response again = as(administrator()).filter(Contract.filter()).header("Idempotency-Key", "finish-key-001")
        .body(finish(NOW, "FINISHED", null)).post(DEPARTURES + "/{code}/finish", "SAL-001");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getBoolean("interrupted")).isTrue();
    assertThat(world.events).hasSize(1);
    assertThat(world.events.get(0).payload()).containsEntry("interrupted", true).doesNotContainValue("Heavy rain closed the trail");
  }

  @Test
  void C_an_interruption_needs_its_cause_and_a_normal_end_takes_none() {
    running();
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "finish-key-001").body(finish(NOW, "INTERRUPTED", null))
        .post(DEPARTURES + "/{code}/finish", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "finish-key-001").body(finish(NOW, "FINISHED", "Rain"))
        .post(DEPARTURES + "/{code}/finish", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "finish-key-001").body(finish(NOW, "CANCELLED", null))
        .post(DEPARTURES + "/{code}/finish", "SAL-001"), 400, "VALIDATION_ERROR");
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_an_end_before_the_start_or_in_the_future_is_422_and_a_departure_not_in_execution_is_422_transition() {
    running();
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "finish-key-001")
        .body(finish(START, "FINISHED", null)).post(DEPARTURES + "/{code}/finish", "SAL-001"), 422, "BUSINESS_RULE_VIOLATION");
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "finish-key-001")
        .body(finish(NOW.plus(Duration.ofHours(1)), "FINISHED", null)).post(DEPARTURES + "/{code}/finish", "SAL-001"), 422, "BUSINESS_RULE_VIOLATION");
    departure(2, START, DepartureStatus.SCHEDULED, TENANT);
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", "finish-key-002").body(finish(NOW, "FINISHED", null))
        .post(DEPARTURES + "/{code}/finish", "SAL-002"), 422, "INVALID_STATUS_TRANSITION");
  }

  // ---- who may call

  @Test
  void C_without_the_permission_every_route_is_403_even_for_the_administrator_and_without_a_token_it_is_401() {
    String noPermission = token("TENANT_ADMINISTRATOR", TENANT, ACCOUNT);
    String customer = token("FINAL_CUSTOMER", TENANT, ACCOUNT, PERMISSION);
    String withoutCollaboratorPermission = token("OPERATIONAL_COLLABORATOR", TENANT, COLLABORATOR);
    for (String bearer : new String[] {noPermission, customer, withoutCollaboratorPermission}) {
      assertError(as(bearer).filter(Contract.answers()).get(DEPARTURES), 403, "FORBIDDEN");
      assertError(as(bearer).filter(Contract.answers()).get(DEPARTURES + "/{code}", "SAL-001"), 403, "FORBIDDEN");
      assertError(as(bearer).filter(Contract.answers()).header("Idempotency-Key", "start-key-0001").body(start(NOW))
          .post(DEPARTURES + "/{code}/executions", "SAL-001"), 403, "FORBIDDEN");
      assertError(as(bearer).filter(Contract.answers()).header("Idempotency-Key", "finish-key-001").body(finish(NOW, "FINISHED", null))
          .post(DEPARTURES + "/{code}/finish", "SAL-001"), 403, "FORBIDDEN");
    }
    assertThat(as(null).get(DEPARTURES).statusCode()).isEqualTo(401);
    assertThat(world.events).isEmpty();
    assertThat(world.departures.get(departure.id())).isEqualTo(departure);
  }

  @Test
  void C_a_collaborator_with_the_permission_still_cannot_schedule_a_departure() {
    Response response = as(collaborator()).header("Idempotency-Key", "schedule-key-1")
        .body(Map.of("serviceId", TOUR.toString(), "scheduledStart", NOW.plus(Duration.ofDays(2)).toString(),
            "scheduledEnd", NOW.plus(Duration.ofDays(2)).plus(Duration.ofHours(8)).toString(), "timeZone", "America/Bogota"))
        .post(DEPARTURES);
    assertThat(response.statusCode()).isEqualTo(403);
  }
}
