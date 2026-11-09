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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * HU-OPS-004 (api spec 006), AC-API-06.1 to -06.7: the reservations in the answer of a departure and the route that saves what each
 * one received, each answer validated against the contract 0.7.0.
 */
@Import(InMemoryWorld.Config.class)
class SaveServicesRouteTest extends ShellTest {

  private static final String DEPARTURES = "/api/v1/operations-cost/departures";
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
  private static final String KEY = "services-key-01";

  private final InMemoryWorld world = InMemoryWorld.WORLD;
  private Departure running;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    Departure scheduled = departure(1, DepartureStatus.SCHEDULED, TENANT);
    running = scheduled.start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
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

  private static Map<String, Object> entry(UUID reservation, String result, int served, String cause, Boolean noShow) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("reservationId", reservation.toString());
    entry.put("result", result);
    entry.put("travelersServed", served);
    if (cause != null) {
      entry.put("cause", cause);
    }
    if (noShow != null) {
      entry.put("noShow", noShow);
    }
    return entry;
  }

  @SafeVarargs
  private static Map<String, Object> body(Map<String, Object>... entries) {
    return Map.of("services", List.of(entries));
  }

  private void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("message")).isNotBlank();
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  private Response save(Map<String, Object> body) {
    return as(administrator()).filter(Contract.filter()).header("Idempotency-Key", KEY).body(body)
        .post(DEPARTURES + "/{code}/services", "SAL-001");
  }

  @Test
  void C_get_departure_answers_its_reservations() {
    Response response = as(administrator()).filter(Contract.filter()).get(DEPARTURES + "/{code}", "SAL-001");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("reservations.customer")).containsExactly("Ana Perez", "Luis Gomez", "Eva Rios");
    assertThat(response.jsonPath().getList("reservations.travelers")).containsExactly(4, 2, 3);
    assertThat(response.jsonPath().getList("reservations.state")).containsExactly("IN_EXECUTION", "IN_EXECUTION", "CANCELLED");
    assertThat(response.jsonPath().getInt("travelersServed")).isZero();
  }

  @Test
  void C_get_departure_without_reservations_still_answers() {
    world.reservationsDown = true;
    Response response = as(administrator()).filter(Contract.filter()).get(DEPARTURES + "/{code}", "SAL-001");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("code")).isEqualTo("SAL-001");
    assertThat(response.jsonPath().getMap("$")).doesNotContainKeys("reservations", "travelersServed");
  }

  @Test
  void C_save_services_records_them_and_answers_the_real_base() {
    Response response = save(body(entry(R1, "DELIVERED", 4, null, null), entry(R2, "DELIVERED", 1, "one stayed", null)));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.header("Location")).isEqualTo(DEPARTURES + "/SAL-001");
    assertThat(response.jsonPath().getInt("travelersServed")).isEqualTo(5);
    assertThat(response.jsonPath().getList("reservations.result")).containsExactly("DELIVERED", "DELIVERED", null);
    assertThat(response.jsonPath().getInt("reservations[0].travelers")).isEqualTo(4);
    assertThat(response.jsonPath().getInt("reservations[1].travelersServed")).isEqualTo(1);
    assertThat(world.serviceRows.get(running.id())).containsOnlyKeys(R1, R2);
    Response read = as(administrator()).filter(Contract.filter()).get(DEPARTURES + "/{code}", "SAL-001");
    assertThat(read.jsonPath().getInt("travelersServed")).isEqualTo(5);
  }

  @Test
  void C_save_services_a_no_show_is_not_delivered_and_stays_charged() {
    Response response = save(body(entry(R2, "NOT_DELIVERED", 0, "did not come, no notice", true)));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.jsonPath().getBoolean("reservations[1].noShow")).isTrue();
    assertThat(response.jsonPath().getInt("travelersServed")).isZero();
  }

  @Test
  void C_save_services_repeat_returns_the_same() {
    assertThat(save(body(entry(R1, "DELIVERED", 4, null, null))).statusCode()).isEqualTo(201);
    Response again = save(body(entry(R1, "DELIVERED", 4, null, null)));
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.header("Location")).isNull();
    assertThat(world.keys).hasSize(1);
  }

  @Test
  void C_save_services_without_cause_is_400() {
    assertError(save(body(entry(R1, "DELIVERED", 3, null, null))), 400, "VALIDATION_ERROR");
    assertError(save(body(entry(R1, "NOT_DELIVERED", 0, null, null))), 400, "VALIDATION_ERROR");
    assertThat(world.serviceRows).isEmpty();
  }

  @Test
  void C_save_services_each_bad_field_is_400() {
    Map<String, Object> noResult = entry(R1, "DELIVERED", 4, null, null);
    noResult.remove("result");
    Map<String, Object> noServed = entry(R1, "DELIVERED", 4, null, null);
    noServed.remove("travelersServed");
    assertError(as(administrator()).header("Idempotency-Key", KEY).body(body(noResult)).post(DEPARTURES + "/{code}/services", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).header("Idempotency-Key", KEY).body(body(noServed)).post(DEPARTURES + "/{code}/services", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).header("Idempotency-Key", KEY).body(body(entry(R1, "MAYBE", 4, null, null)))
        .post(DEPARTURES + "/{code}/services", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).header("Idempotency-Key", KEY).body(body(entry(R1, "DELIVERED", -1, null, null)))
        .post(DEPARTURES + "/{code}/services", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).header("Idempotency-Key", KEY).body(Map.of("services", List.of()))
        .post(DEPARTURES + "/{code}/services", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).header("Idempotency-Key", KEY).body(Map.of("services", List.of(), "other", 1))
        .post(DEPARTURES + "/{code}/services", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).body(body(entry(R1, "DELIVERED", 4, null, null))).post(DEPARTURES + "/{code}/services", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).header("Idempotency-Key", "short").body(body(entry(R1, "DELIVERED", 4, null, null)))
        .post(DEPARTURES + "/{code}/services", "SAL-001"), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_save_services_more_served_than_reserved_or_a_stranger_is_422() {
    assertError(save(body(entry(R2, "DELIVERED", 3, null, null))), 422, "BUSINESS_RULE_VIOLATION");
    assertError(save(body(entry(new UUID(0, 0x999), "DELIVERED", 1, "x", null))), 422, "BUSINESS_RULE_VIOLATION");
    assertError(save(body(entry(R3, "NOT_DELIVERED", 0, "cancelled", null))), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(world.serviceRows).isEmpty();
  }

  @Test
  void C_save_services_on_a_departure_not_in_execution_is_422_invalid_status_transition() {
    departure(2, DepartureStatus.SCHEDULED, TENANT);
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(body(entry(R1, "DELIVERED", 4, null, null)))
        .post(DEPARTURES + "/{code}/services", "SAL-002"), 422, "INVALID_STATUS_TRANSITION");
  }

  @Test
  void C_save_services_reservations_down_is_503_and_writes_nothing() {
    world.reservationsDown = true;
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(body(entry(R1, "DELIVERED", 4, null, null)))
        .post(DEPARTURES + "/{code}/services", "SAL-001"), 503, "SERVICE_UNAVAILABLE");
    assertThat(world.serviceRows).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void C_save_services_without_the_permission_is_403_and_without_a_token_401() {
    String noPermission = token("TENANT_ADMINISTRATOR", TENANT);
    String customer = token("FINAL_CUSTOMER", TENANT, PERMISSION);
    String collaboratorWithout = token("OPERATIONAL_COLLABORATOR", TENANT);
    for (String bearer : new String[] {noPermission, customer, collaboratorWithout}) {
      assertError(as(bearer).filter(Contract.answers()).header("Idempotency-Key", KEY).body(body(entry(R1, "DELIVERED", 4, null, null)))
          .post(DEPARTURES + "/{code}/services", "SAL-001"), 403, "FORBIDDEN");
    }
    assertThat(as(null).body(body(entry(R1, "DELIVERED", 4, null, null))).post(DEPARTURES + "/{code}/services", "SAL-001").statusCode()).isEqualTo(401);
    assertThat(world.serviceRows).isEmpty();
  }

  @Test
  void C_another_tenants_departure_is_404() {
    departure(3, DepartureStatus.IN_EXECUTION, OTHER_TENANT);
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(body(entry(R1, "DELIVERED", 4, null, null)))
        .post(DEPARTURES + "/{code}/services", "SAL-003"), 404, "NOT_FOUND");
  }
}
