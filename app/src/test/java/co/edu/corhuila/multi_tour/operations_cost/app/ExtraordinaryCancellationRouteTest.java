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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * HU-OPS-005 (api spec 009), AC-API-05.1 to -05.6: the route that records the extraordinary cancellation of a reservation of a running
 * departure and the departure that answers it next to the reservation, each answer validated against the contract 0.10.0.
 */
@Import(InMemoryWorld.Config.class)
class ExtraordinaryCancellationRouteTest extends ShellTest {

  private static final String DEPARTURES = "/api/v1/operations-cost/departures";
  private static final String ROUTE = DEPARTURES + "/{code}/reservations/{reservation}/extraordinary-cancellation";
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
  private static final String KEY = "cancel-key-0001";
  private static final String WHY = "the traveler had a medical emergency";

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

  private void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("message")).isNotBlank();
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  private Response cancel(UUID reservation, String justification) {
    return as(administrator()).filter(Contract.filter()).header("Idempotency-Key", KEY).body(Map.of("justification", justification))
        .post(ROUTE, "SAL-001", reservation);
  }

  private Response refused(String bearer, String code, UUID reservation, Map<String, Object> body, String key) {
    RequestSpecification request = as(bearer).filter(Contract.answers());
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return request.body(body).post(ROUTE, code, reservation);
  }

  @Test
  void C_record_extraordinary_cancellation_is_201_with_its_location_and_the_cancellation_in_the_answer() {
    Response response = cancel(R1, WHY);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.header("Location")).isEqualTo(DEPARTURES + "/SAL-001");
    assertThat(response.jsonPath().getString("reservations[0].extraordinaryCancellation.justification")).isEqualTo(WHY);
    assertThat(response.jsonPath().getString("reservations[0].extraordinaryCancellation.recordedBy")).isEqualTo(ACCOUNT.toString());
    assertThat(response.jsonPath().getString("reservations[0].extraordinaryCancellation.recordedAt")).isNotBlank();
    assertThat(response.jsonPath().getMap("reservations[1]")).doesNotContainKey("extraordinaryCancellation");
    assertThat(world.cancellationRows.get(running.id())).containsOnlyKeys(R1);
    assertThat(world.events).extracting(e -> e.eventType()).containsExactly("ExtraordinaryCancellationRecorded");
  }

  @Test
  void C_get_departure_answers_the_extraordinary_cancellation_of_a_reservation() {
    cancel(R2, WHY);
    Response read = as(administrator()).filter(Contract.filter()).get(DEPARTURES + "/{code}", "SAL-001");
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(read.jsonPath().getString("reservations[1].extraordinaryCancellation.justification")).isEqualTo(WHY);
    assertThat(read.jsonPath().getMap("reservations[0]")).doesNotContainKey("extraordinaryCancellation");
  }

  @Test
  void C_repeat_with_the_same_key_returns_the_same() {
    assertThat(cancel(R1, WHY).statusCode()).isEqualTo(201);
    Response again = cancel(R1, WHY);
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.header("Location")).isNull();
    assertThat(world.events).hasSize(1);
    assertThat(world.keys).hasSize(1);
  }

  @Test
  void C_extraordinary_cancellation_without_justification_is_400() {
    for (String bad : new String[] {"", "   ", "j".repeat(501)}) {
      assertError(refused(administrator(), "SAL-001", R1, Map.of("justification", bad), KEY), 400, "VALIDATION_ERROR");
    }
    assertError(refused(administrator(), "SAL-001", R1, Map.of(), KEY), 400, "VALIDATION_ERROR");
    assertError(refused(administrator(), "SAL-001", R1, Map.of("justification", WHY, "other", 1), KEY), 400, "VALIDATION_ERROR");
    assertError(refused(administrator(), "SAL-001", R1, Map.of("justification", WHY), null), 400, "VALIDATION_ERROR");
    assertError(refused(administrator(), "SAL-001", R1, Map.of("justification", WHY), "short"), 400, "VALIDATION_ERROR");
    assertThat(world.cancellationRows).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void C_a_stranger_a_reservation_not_in_execution_or_a_second_cancellation_is_422_business_rule_violation() {
    assertError(refused(administrator(), "SAL-001", new UUID(0, 0x999), Map.of("justification", WHY), KEY), 422, "BUSINESS_RULE_VIOLATION");
    assertError(refused(administrator(), "SAL-001", R3, Map.of("justification", WHY), KEY), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(cancel(R1, WHY).statusCode()).isEqualTo(201);
    assertError(refused(administrator(), "SAL-001", R1, Map.of("justification", "another"), "cancel-key-0002"), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(world.cancellationRows.get(running.id())).containsOnlyKeys(R1);
  }

  @Test
  void C_departure_not_in_execution_is_422_invalid_status_transition() {
    departure(2, DepartureStatus.SCHEDULED, TENANT);
    assertError(refused(administrator(), "SAL-002", R1, Map.of("justification", WHY), KEY), 422, "INVALID_STATUS_TRANSITION");
  }

  @Test
  void C_reservations_down_is_503_and_writes_nothing() {
    world.reservationsDown = true;
    assertError(refused(administrator(), "SAL-001", R1, Map.of("justification", WHY), KEY), 503, "SERVICE_UNAVAILABLE");
    assertThat(world.cancellationRows).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void C_extraordinary_cancellation_without_the_permission_is_403_and_without_a_token_401() {
    String noPermission = token("TENANT_ADMINISTRATOR", TENANT);
    String customer = token("FINAL_CUSTOMER", TENANT, PERMISSION);
    String collaboratorWithout = token("OPERATIONAL_COLLABORATOR", TENANT);
    String optional = token("MANAGER", TENANT, PERMISSION);
    for (String bearer : new String[] {noPermission, customer, collaboratorWithout, optional}) {
      assertError(refused(bearer, "SAL-001", R1, Map.of("justification", WHY), KEY), 403, "FORBIDDEN");
    }
    assertThat(as(null).body(Map.of("justification", WHY)).header("Idempotency-Key", KEY).post(ROUTE, "SAL-001", R1).statusCode()).isEqualTo(401);
    assertThat(world.cancellationRows).isEmpty();
  }

  @Test
  void C_a_collaborator_with_the_permission_can_record_it() {
    String collaborator = token("OPERATIONAL_COLLABORATOR", TENANT, PERMISSION);
    Response response = as(collaborator).filter(Contract.filter()).header("Idempotency-Key", KEY).body(Map.of("justification", WHY)).post(ROUTE, "SAL-001", R1);
    assertThat(response.statusCode()).isEqualTo(201);
  }

  @Test
  void C_another_tenants_departure_is_404() {
    departure(3, DepartureStatus.IN_EXECUTION, OTHER_TENANT);
    assertError(refused(administrator(), "SAL-003", R1, Map.of("justification", WHY), KEY), 404, "NOT_FOUND");
  }
}
