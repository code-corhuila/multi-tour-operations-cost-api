package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServiceRecord;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServiceResult;
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
 * HU-OPS-007 (api spec 007), AC-API-07.1 to -07.11: the routes that record and read the operational costs of a departure, each answer
 * validated against the contract 0.8.0. Who may call is decided by the permission of the token.
 */
@Import(InMemoryWorld.Config.class)
class CostRoutesTest extends ShellTest {

  private static final String COSTS = "/api/v1/operations-cost/departures/{code}/costs";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final UUID R1 = new UUID(0, 0x101);
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private static final Instant START = NOW.minus(Duration.ofHours(2));
  private static final String PERMISSION = "MANAGE_DEPARTURE_EXECUTION";
  private static final String KEY = "cost-key-00001";

  private final InMemoryWorld world = InMemoryWorld.WORLD;
  private Departure running;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true, 21_000_000L));
    running = departure(1, DepartureStatus.SCHEDULED, TENANT).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(running.id(), running);
    world.serviceRows.computeIfAbsent(running.id(), id -> new LinkedHashMap<>()).put(R1, new ServiceRecord(R1, ServiceResult.DELIVERED, 4, null, false));
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

  private static Map<String, Object> expense(long cents, String justification) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("concept", "Fuel of the boat");
    body.put("mode", "MANUAL");
    body.put("amountCents", cents);
    if (justification != null) {
      body.put("justification", justification);
    }
    return body;
  }

  private static Map<String, Object> calculation() {
    return Map.of("concept", "Boat and guide", "mode", "CALCULATED");
  }

  private void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("message")).isNotBlank();
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  private Response record(Map<String, Object> body) {
    return as(administrator()).filter(Contract.filter()).header("Idempotency-Key", KEY).body(body).post(COSTS, "SAL-001");
  }

  @Test
  void C_record_cost_a_real_expense_is_201_with_its_location_and_no_reservation_changes() {
    Response response = record(expense(50_000L, null));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.header("Location")).isEqualTo("/api/v1/operations-cost/departures/SAL-001/costs");
    assertThat(response.jsonPath().getLong("amountCents")).isEqualTo(50_000L);
    assertThat(response.jsonPath().getString("mode")).isEqualTo("MANUAL");
    assertThat(response.jsonPath().getBoolean("withoutParametrization")).isFalse();
    assertThat(response.jsonPath().getMap("$")).doesNotContainKeys("unitCostCents", "travelersServed", "justification");
    assertThat(world.serviceRows.get(running.id())).hasSize(1);
    assertThat(world.events).singleElement().satisfies(event -> assertThat(event.eventType()).isEqualTo("OperationalCostRecorded"));
  }

  @Test
  void C_record_calculated_cost_is_the_catalogued_cost_times_the_travelers_served() {
    Response response = record(calculation());
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.jsonPath().getLong("amountCents")).isEqualTo(84_000_000L);
    assertThat(response.jsonPath().getLong("unitCostCents")).isEqualTo(21_000_000L);
    assertThat(response.jsonPath().getInt("travelersServed")).isEqualTo(4);
  }

  @Test
  void C_record_calculated_cost_without_travelers_served_is_422_business_rule() {
    world.serviceRows.clear();
    assertError(record(calculation()), 422, "BUSINESS_RULE_VIOLATION");
    assertThat(world.costRows).isEmpty();
  }

  @Test
  void C_record_cost_without_parametrization_a_calculation_is_422_and_a_real_expense_needs_its_justification() {
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    assertError(record(calculation()), 422, "BUSINESS_RULE_VIOLATION");
    assertError(record(expense(50_000L, null)), 422, "BUSINESS_RULE_VIOLATION");
    Response response = record(expense(50_000L, "Paid at the dock, no catalogued cost"));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.jsonPath().getBoolean("withoutParametrization")).isTrue();
    assertThat(response.jsonPath().getString("justification")).isEqualTo("Paid at the dock, no catalogued cost");
  }

  @Test
  void C_record_cost_on_a_departure_that_has_not_started_is_422_invalid_status_transition() {
    departure(2, DepartureStatus.SCHEDULED, TENANT);
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(expense(50_000L, null))
        .post(COSTS, "SAL-002"), 422, "INVALID_STATUS_TRANSITION");
  }

  @Test
  void C_record_cost_repeat_returns_the_same_line() {
    Response first = record(expense(50_000L, null));
    Response again = record(expense(50_000L, null));
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.header("Location")).isNull();
    assertThat(again.jsonPath().getString("costId")).isEqualTo(first.jsonPath().getString("costId"));
    assertThat(world.costRows).hasSize(1);
  }

  @Test
  void C_record_cost_each_bad_field_is_400() {
    Map<String, Object> noConcept = expense(50_000L, null);
    noConcept.remove("concept");
    Map<String, Object> noMode = expense(50_000L, null);
    noMode.remove("mode");
    Map<String, Object> unknownMode = expense(50_000L, null);
    unknownMode.put("mode", "MAYBE");
    Map<String, Object> calculatedWithAmount = calculation().entrySet().stream()
        .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    calculatedWithAmount.put("amountCents", 1L);
    Map<String, Object> extra = expense(50_000L, null);
    extra.put("other", 1);
    for (Map<String, Object> bad : List.of(noConcept, noMode, unknownMode, calculatedWithAmount, extra, expense(0L, null), expense(-5L, null))) {
      assertError(as(administrator()).header("Idempotency-Key", KEY).body(bad).post(COSTS, "SAL-001"), 400, "VALIDATION_ERROR");
    }
    assertError(as(administrator()).body(expense(50_000L, null)).post(COSTS, "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(administrator()).header("Idempotency-Key", "short").body(expense(50_000L, null)).post(COSTS, "SAL-001"), 400, "VALIDATION_ERROR");
    assertThat(world.costRows).isEmpty();
  }

  @Test
  void C_the_catalog_down_is_503_and_nothing_is_written() {
    world.catalogDown = true;
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(expense(50_000L, "x")).post(COSTS, "SAL-001"),
        503, "SERVICE_UNAVAILABLE");
    assertError(as(administrator()).filter(Contract.answers()).get(COSTS, "SAL-001"), 503, "SERVICE_UNAVAILABLE");
    assertThat(world.costRows).isEmpty();
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_list_costs_answers_the_lines_the_total_the_travelers_served_and_the_catalogued_cost() {
    record(calculation());
    as(administrator()).header("Idempotency-Key", "cost-key-00002").body(expense(50_000L, null)).post(COSTS, "SAL-001");
    Response response = as(administrator()).filter(Contract.filter()).get(COSTS, "SAL-001");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("data")).hasSize(2);
    assertThat(response.jsonPath().getList("data.mode")).containsExactly("CALCULATED", "MANUAL");
    assertThat(response.jsonPath().getLong("totalCents")).isEqualTo(84_050_000L);
    assertThat(response.jsonPath().getInt("travelersServed")).isEqualTo(4);
    assertThat(response.jsonPath().getLong("baseCostCents")).isEqualTo(21_000_000L);
  }

  @Test
  void C_list_costs_of_a_tour_without_cost_omits_the_catalogued_cost_and_an_empty_departure_has_no_lines() {
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    Response response = as(administrator()).filter(Contract.filter()).get(COSTS, "SAL-001");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("data")).isEmpty();
    assertThat(response.jsonPath().getLong("totalCents")).isZero();
    assertThat(response.jsonPath().getMap("$")).doesNotContainKey("baseCostCents");
  }

  @Test
  void C_without_the_permission_both_routes_are_403_and_without_a_token_401() {
    String noPermission = token("TENANT_ADMINISTRATOR", TENANT);
    String customer = token("FINAL_CUSTOMER", TENANT, PERMISSION);
    String collaboratorWithout = token("OPERATIONAL_COLLABORATOR", TENANT);
    for (String bearer : new String[] {noPermission, customer, collaboratorWithout}) {
      assertError(as(bearer).filter(Contract.answers()).header("Idempotency-Key", KEY).body(expense(50_000L, null)).post(COSTS, "SAL-001"), 403, "FORBIDDEN");
      assertError(as(bearer).filter(Contract.answers()).get(COSTS, "SAL-001"), 403, "FORBIDDEN");
    }
    assertThat(as(null).get(COSTS, "SAL-001").statusCode()).isEqualTo(401);
    assertThat(as(null).body(expense(50_000L, null)).post(COSTS, "SAL-001").statusCode()).isEqualTo(401);
    assertThat(world.costRows).isEmpty();
  }

  @Test
  void C_a_collaborator_with_the_permission_records_and_reads() {
    String collaborator = token("OPERATIONAL_COLLABORATOR", TENANT, PERMISSION);
    assertThat(as(collaborator).header("Idempotency-Key", KEY).body(expense(50_000L, null)).post(COSTS, "SAL-001").statusCode()).isEqualTo(201);
    assertThat(as(collaborator).get(COSTS, "SAL-001").statusCode()).isEqualTo(200);
  }

  @Test
  void C_another_tenants_departure_is_404_for_both_routes() {
    departure(3, DepartureStatus.IN_EXECUTION, OTHER_TENANT);
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(expense(50_000L, "x")).post(COSTS, "SAL-003"),
        404, "NOT_FOUND");
    assertError(as(administrator()).filter(Contract.answers()).get(COSTS, "SAL-003"), 404, "NOT_FOUND");
  }
}
