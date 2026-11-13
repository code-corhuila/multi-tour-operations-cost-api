package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ReservationOfDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ReservationState;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * HU-OPS-008 (api spec 011), AC-API-11.1 to -11.7: the route that records the personal emergency of a reservation of a running departure
 * and the departure that lists it, each answer validated against the contract 0.12.0.
 */
@Import(InMemoryWorld.Config.class)
class RegisterNoveltyRouteTest extends ShellTest {

  private static final String DEPARTURES = "/api/v1/operations-cost/departures";
  private static final String ROUTE = DEPARTURES + "/{code}/novelties";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final UUID R1 = new UUID(0, 0x101);
  private static final UUID R2 = new UUID(0, 0x102);
  private static final UUID R3 = new UUID(0, 0x103);
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private static final Instant START = NOW.minus(Duration.ofHours(2));
  private static final String PERMISSION = "MANAGE_DEPARTURE_EXECUTION";
  private static final String KEY = "emergency-key-0001";
  private static final String DETAIL = "a traveler fainted on the trail";

  private final InMemoryWorld world = InMemoryWorld.WORLD;
  private Departure running;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    running = departure(1, DepartureStatus.SCHEDULED, TENANT).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(running.id(), running);
    world.reservationRows.put(running.id(), List.of(
        new ReservationOfDeparture(R1, "Ana Perez", 4, ReservationState.IN_EXECUTION),
        new ReservationOfDeparture(R2, "Luis Gomez", 2, ReservationState.IN_EXECUTION),
        new ReservationOfDeparture(R3, "Eva Rios", 3, ReservationState.CANCELLED)));
  }

  private Departure departure(int n, DepartureStatus status, UUID tenant) {
    Departure created = new Departure(new UUID(0, 1000 + n), tenant, String.format("SAL-%03d", n), TOUR, START, START.plus(Duration.ofHours(8)),
        "America/Bogota", status, ACCOUNT, START.minus(Duration.ofDays(3)));
    world.departures.put(created.id(), created);
    return created;
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

  private static Map<String, Object> body(UUID reservation, Object affected, String detail) {
    Map<String, Object> body = new HashMap<>();
    body.put("reservationId", reservation.toString());
    body.put("type", "PERSONAL_EMERGENCY");
    if (detail != null) {
      body.put("detail", detail);
    }
    if (affected != null) {
      body.put("affected", affected);
    }
    return body;
  }

  private void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("message")).isNotBlank();
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  private Response record(UUID reservation, int affected) {
    return as(administrator()).filter(Contract.filter()).header("Idempotency-Key", KEY).body(body(reservation, affected, DETAIL)).post(ROUTE, "SAL-001");
  }

  private Response refused(String bearer, String code, Map<String, Object> body, String key) {
    RequestSpecification request = as(bearer).filter(Contract.answers());
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return request.body(body).post(ROUTE, code);
  }

  @Test
  void C_record_personal_emergency_is_201_with_its_location_and_the_novelty_in_the_answer() {
    Response response = record(R1, 2);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.header("Location")).isEqualTo(DEPARTURES + "/SAL-001");
    assertThat(response.jsonPath().getString("status")).isEqualTo("IN_EXECUTION");
    assertThat(response.jsonPath().getString("novelties[0].type")).isEqualTo("PERSONAL_EMERGENCY");
    assertThat(response.jsonPath().getString("novelties[0].detail")).isEqualTo(DETAIL);
    assertThat(response.jsonPath().getString("novelties[0].who")).isEqualTo(ACCOUNT.toString());
    assertThat(response.jsonPath().getString("novelties[0].reservationId")).isEqualTo(R1.toString());
    assertThat(response.jsonPath().getInt("novelties[0].affected")).isEqualTo(2);
    assertThat(response.jsonPath().getString("novelties[0].at")).isNotBlank();
    assertThat(world.emergencyRows.get(running.id())).containsOnlyKeys(R1);
    assertThat(world.events).extracting(e -> e.eventType()).containsExactly("PersonalEmergencyRecorded");
    assertThat(world.reservationRows.get(running.id()).get(0).state()).isEqualTo(ReservationState.IN_EXECUTION);
  }

  @Test
  void C_the_answer_has_no_amount() {
    String answer = record(R1, 2).asString();
    assertThat(answer).doesNotContain("amount").doesNotContain("refund");
  }

  @Test
  void C_get_departure_lists_its_personal_emergencies_and_an_empty_list_without_them() {
    Response none = as(administrator()).filter(Contract.filter()).get(DEPARTURES + "/{code}", "SAL-001");
    assertThat(none.statusCode()).isEqualTo(200);
    assertThat(none.jsonPath().getList("novelties")).isEmpty();
    record(R2, 1);
    Response read = as(administrator()).filter(Contract.filter()).get(DEPARTURES + "/{code}", "SAL-001");
    assertThat(read.jsonPath().getList("novelties")).hasSize(1);
    assertThat(read.jsonPath().getString("novelties[0].reservationId")).isEqualTo(R2.toString());
  }

  @Test
  void C_repeat_with_the_same_key_returns_the_same() {
    assertThat(record(R1, 2).statusCode()).isEqualTo(201);
    Response again = record(R1, 2);
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.header("Location")).isNull();
    assertThat(again.jsonPath().getList("novelties")).hasSize(1);
    assertThat(world.events).hasSize(1);
    assertThat(world.keys).hasSize(1);
  }

  @Test
  void C_personal_emergency_without_detail_or_affected_is_400() {
    for (String bad : new String[] {"", "   ", "d".repeat(1001)}) {
      assertError(refused(administrator(), "SAL-001", body(R1, 2, bad), KEY), 400, "VALIDATION_ERROR");
    }
    assertError(refused(administrator(), "SAL-001", body(R1, 2, null), KEY), 400, "VALIDATION_ERROR");
    for (Object bad : new Object[] {null, 0, -1}) {
      assertError(refused(administrator(), "SAL-001", body(R1, bad, DETAIL), KEY), 400, "VALIDATION_ERROR");
    }
    Map<String, Object> unknown = body(R1, 2, DETAIL);
    unknown.put("other", 1);
    assertError(refused(administrator(), "SAL-001", unknown, KEY), 400, "VALIDATION_ERROR");
    Map<String, Object> notAUuid = body(R1, 2, DETAIL);
    notAUuid.put("reservationId", "not-a-uuid");
    assertError(refused(administrator(), "SAL-001", notAUuid, KEY), 400, "VALIDATION_ERROR");
    assertError(refused(administrator(), "SAL-001", body(R1, 2, DETAIL), null), 400, "VALIDATION_ERROR");
    assertError(refused(administrator(), "SAL-001", body(R1, 2, DETAIL), "short"), 400, "VALIDATION_ERROR");
    assertThat(world.emergencyRows).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void C_affected_above_the_travelers_and_the_ordinary_novelty_are_422_business_rule() {
    assertError(refused(administrator(), "SAL-001", body(R1, 5, DETAIL), KEY), 422, "BUSINESS_RULE_VIOLATION");
    Map<String, Object> novelty = body(R1, 2, DETAIL);
    novelty.put("type", "NOVELTY");
    assertError(refused(administrator(), "SAL-001", novelty, KEY), 422, "BUSINESS_RULE_VIOLATION");
    assertError(refused(administrator(), "SAL-001", body(R3, 1, DETAIL), KEY), 422, "BUSINESS_RULE_VIOLATION");
    assertError(refused(administrator(), "SAL-001", body(new UUID(0, 0x999), 1, DETAIL), KEY), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(world.emergencyRows).isEmpty();
  }

  @Test
  void C_a_second_emergency_of_a_reservation_is_422_and_a_departure_not_in_execution_is_422_invalid_status_transition() {
    record(R1, 2);
    assertError(refused(administrator(), "SAL-001", body(R1, 1, "another"), "emergency-key-0002"), 422, "BUSINESS_RULE_VIOLATION");
    departure(2, DepartureStatus.SCHEDULED, TENANT);
    assertError(refused(administrator(), "SAL-002", body(R1, 1, DETAIL), "emergency-key-0003"), 422, "INVALID_STATUS_TRANSITION");
    assertThat(world.events).hasSize(1);
  }

  @Test
  void C_without_the_permission_it_is_403_a_customer_too_and_without_a_token_401() {
    Map<String, Object> request = body(R1, 2, DETAIL);
    assertError(refused(token("OPERATIONAL_COLLABORATOR", TENANT), "SAL-001", request, KEY), 403, "FORBIDDEN");
    assertError(refused(token("FINAL_CUSTOMER", TENANT, PERMISSION), "SAL-001", request, KEY), 403, "FORBIDDEN");
    assertError(refused(null, "SAL-001", request, KEY), 401, "UNAUTHORIZED");
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_a_decimal_or_a_text_for_affected_is_400() {
    assertError(refused(administrator(), "SAL-001", body(R1, 1.5, DETAIL), KEY), 400, "VALIDATION_ERROR");
    assertError(refused(administrator(), "SAL-001", body(R1, "2", DETAIL), KEY), 400, "VALIDATION_ERROR");
    assertThat(world.emergencyRows).isEmpty();
  }

  @Test
  void C_an_administrator_without_the_permission_is_403_and_a_collaborator_with_it_records() {
    assertError(refused(token("TENANT_ADMINISTRATOR", TENANT), "SAL-001", body(R1, 2, DETAIL), KEY), 403, "FORBIDDEN");
    Response response = as(token("OPERATIONAL_COLLABORATOR", TENANT, PERMISSION)).filter(Contract.filter()).header("Idempotency-Key", KEY)
        .body(body(R1, 2, DETAIL)).post(ROUTE, "SAL-001");
    assertThat(response.statusCode()).isEqualTo(201);
  }

  @Test
  void C_a_key_used_for_another_departure_is_422() {
    Departure other = departure(2, DepartureStatus.SCHEDULED, TENANT).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(other.id(), other);
    world.reservationRows.put(other.id(), List.of(new ReservationOfDeparture(R1, "Ana Perez", 4, ReservationState.IN_EXECUTION)));
    record(R1, 2);
    assertError(refused(administrator(), "SAL-002", body(R1, 2, DETAIL), KEY), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test
  void C_the_exceptional_reader_of_the_platform_never_gets_the_emergencies() {
    record(R1, 2);
    String exceptional = Tokens.valid(Tokens.claims(ACCOUNT.toString(), 600, List.of("PLATFORM_ADMINISTRATOR"), TENANT.toString())
        .claim("access", "exceptional").claim("scope", List.of("OPERATIONS")).claim("authorizationId", "auth-1"));
    Response read = as(exceptional).filter(Contract.filter()).get(DEPARTURES + "/{code}", "SAL-001");
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(read.jsonPath().getMap("$")).doesNotContainKey("novelties");
    assertThat(read.asString()).doesNotContain(DETAIL);
  }

  @Test
  void C_the_departure_of_another_tenant_or_one_that_does_not_exist_is_404() {
    departure(3, DepartureStatus.SCHEDULED, OTHER_TENANT);
    assertError(refused(administrator(), "SAL-003", body(R1, 2, DETAIL), KEY), 404, "NOT_FOUND");
    assertError(refused(administrator(), "SAL-099", body(R1, 2, DETAIL), KEY), 404, "NOT_FOUND");
  }

  @Test
  void C_reservations_down_is_503_and_writes_nothing() {
    world.reservationsDown = true;
    assertError(refused(administrator(), "SAL-001", body(R1, 2, DETAIL), KEY), 503, "SERVICE_UNAVAILABLE");
    assertThat(world.emergencyRows).isEmpty();
    assertThat(world.events).isEmpty();
  }
}
