package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * The two internal routes of the saga that changes the vehicles of a departure (api spec 008, AC-API-08.1 to -08.5): the preview and the
 * replacement, only the SERVICE role, each answer validated against the contract.
 */
@Import(InMemoryWorld.Config.class)
class VehicleChangeRoutesTest extends ShellTest {

  private static final String BASE = "/api/v1/operations-cost/internal/tenants/{tenant}/departures/{code}";
  private static final String PREVIEW = BASE + "/vehicle-changes/preview";
  private static final String APPLY = BASE + "/vehicles";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final Instant START = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final String KEY = "saga-step-0001";

  private final InMemoryWorld world = InMemoryWorld.WORLD;
  private Departure departure;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    departure = departure(1, DepartureStatus.SCHEDULED);
  }

  private Departure departure(int n, DepartureStatus status) {
    Departure created = new Departure(new UUID(0, 1000 + n), TENANT, String.format("SAL-%03d", n), TOUR, START, END, "America/Bogota",
        status, ACCOUNT, Instant.now());
    world.departures.put(created.id(), created);
    return created;
  }

  private Vehicle vehicle(int n, int capacity, UUID tenant) {
    Vehicle v = new Vehicle(new UUID(0, 2000 + n), tenant, "Vehicle " + n, capacity, ACCOUNT, Instant.now());
    world.vehicleRows.put(v.id(), v);
    return v;
  }

  private static String service() {
    return Tokens.valid(Tokens.claims("workflow-change-departure-vehicles", 600, List.of("SERVICE"), null));
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

  private static Map<String, Object> ids(Object... vehicleIds) {
    return Map.of("vehicleIds", List.of(vehicleIds).stream().map(Object::toString).toList());
  }

  private static void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_preview_answers_the_capacities_and_the_departure_id_and_writes_nothing() {
    Vehicle van = vehicle(1, 12, TENANT);
    Vehicle bus = vehicle(2, 30, TENANT);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id())));
    Response response = as(service()).filter(Contract.filter()).body(ids(van.id(), bus.id())).post(PREVIEW, TENANT, "SAL-001");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("departureId")).isEqualTo(departure.id().toString());
    assertThat(response.jsonPath().getInt("currentEffectiveCapacity")).isEqualTo(12);
    assertThat(response.jsonPath().getInt("newEffectiveCapacity")).isEqualTo(18);
    assertThat(world.assignments.get(departure.id())).containsExactly(van.id());
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_preview_and_apply_refuse_with_422_and_change_nothing() {
    Vehicle van = vehicle(1, 12, TENANT);
    departure(2, DepartureStatus.IN_EXECUTION);
    assertError(as(service()).filter(Contract.answers()).body(ids(van.id())).post(PREVIEW, TENANT, "SAL-002"), 422, "INVALID_STATUS_TRANSITION");
    assertError(as(service()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(ids(van.id())).put(APPLY, TENANT, "SAL-002"), 422,
        "INVALID_STATUS_TRANSITION");
    UUID unknown = new UUID(0, 9999);
    assertError(as(service()).filter(Contract.answers()).body(ids(unknown)).post(PREVIEW, TENANT, "SAL-001"), 422, "BUSINESS_RULE_VIOLATION");
    assertError(as(service()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(ids(unknown)).put(APPLY, TENANT, "SAL-001"), 422,
        "BUSINESS_RULE_VIOLATION");
    assertThat(world.assignments).isEmpty();
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_another_tenants_departure_is_404() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertError(as(service()).filter(Contract.answers()).body(ids(van.id())).post(PREVIEW, OTHER_TENANT, "SAL-001"), 404, "NOT_FOUND");
    assertError(as(service()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(ids(van.id())).put(APPLY, OTHER_TENANT, "SAL-001"), 404,
        "NOT_FOUND");
  }

  @Test
  void C_a_bad_body_a_bad_tenant_or_a_missing_key_is_400() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertError(as(service()).filter(Contract.answers()).body(Map.of("vehicleIds", List.of())).post(PREVIEW, TENANT, "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).body(ids(van.id())).post(PREVIEW, "not-a-uuid", "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).body(ids(van.id())).put(APPLY, TENANT, "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(ids(van.id())).put(APPLY, TENANT, "SAL-1"), 400,
        "VALIDATION_ERROR");
  }

  @Test
  void C_a_catalog_that_does_not_answer_is_503_and_writes_nothing() {
    Vehicle van = vehicle(1, 12, TENANT);
    world.catalogDown = true;
    assertError(as(service()).filter(Contract.answers()).body(ids(van.id())).post(PREVIEW, TENANT, "SAL-001"), 503, "SERVICE_UNAVAILABLE");
    assertError(as(service()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(ids(van.id())).put(APPLY, TENANT, "SAL-001"), 503,
        "SERVICE_UNAVAILABLE");
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_apply_replaces_the_set_and_answers_the_departure() {
    Vehicle van = vehicle(1, 12, TENANT);
    Vehicle bus = vehicle(2, 30, TENANT);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id())));
    Response response = as(service()).filter(Contract.filter()).header("Idempotency-Key", KEY).body(ids(bus.id())).put(APPLY, TENANT, "SAL-001");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("vehicles.name")).containsExactly("Vehicle 2");
    assertThat(response.jsonPath().getInt("capacity")).isEqualTo(18);
    assertThat(world.assignments.get(departure.id())).containsExactly(bus.id());
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaVehiclesChanged");
      assertThat(event.payload()).containsEntry("vehicleTotalCapacity", 30);
      assertThat(event.correlationId()).isEqualTo(response.header("X-Correlation-Id"));
    });
  }

  @Test
  void C_apply_again_answers_200_with_the_same_departure_and_writes_nothing_more() {
    Vehicle bus = vehicle(2, 30, TENANT);
    as(service()).filter(Contract.filter()).header("Idempotency-Key", KEY).body(ids(bus.id())).put(APPLY, TENANT, "SAL-001");
    Response again = as(service()).filter(Contract.filter()).header("Idempotency-Key", KEY).body(ids(bus.id())).put(APPLY, TENANT, "SAL-001");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getList("vehicles")).hasSize(1);
    assertThat(world.events).hasSize(1);
  }

  @Test
  void C_no_token_is_401_and_a_person_is_403() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertError(as(null).filter(Contract.answers()).body(ids(van.id())).post(PREVIEW, TENANT, "SAL-001"), 401, "UNAUTHORIZED");
    assertError(as(null).filter(Contract.answers()).header("Idempotency-Key", KEY).body(ids(van.id())).put(APPLY, TENANT, "SAL-001"), 401,
        "UNAUTHORIZED");
    assertError(as(administrator()).filter(Contract.answers()).body(ids(van.id())).post(PREVIEW, TENANT, "SAL-001"), 403, "FORBIDDEN");
    assertError(as(administrator()).filter(Contract.answers()).header("Idempotency-Key", KEY).body(ids(van.id())).put(APPLY, TENANT, "SAL-001"), 403,
        "FORBIDDEN");
    assertThat(world.events).isEmpty();
  }
}
