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
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** POST /departures/{code}/vehicles and the vehicles of POST /departures: each answer validated against the contract (api spec 003, AC-API-03.4 to -03.13). */
@Import(InMemoryWorld.Config.class)
class AddDepartureVehiclesRouteTest extends ShellTest {

  private static final String DEPARTURES = "/api/v1/operations-cost/departures";
  private static final String ROUTE = DEPARTURES + "/{code}/vehicles";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final Instant START = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
  private static final Instant END = START.plus(Duration.ofHours(8));

  private final InMemoryWorld world = InMemoryWorld.WORLD;
  private Departure departure;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    departure = departure(1, START, END, DepartureStatus.SCHEDULED);
  }

  private Departure departure(int n, Instant start, Instant end, DepartureStatus status) {
    Departure created = new Departure(new UUID(0, 1000 + n), TENANT, String.format("SAL-%03d", n), TOUR, start, end, "America/Bogota",
        status, ACCOUNT, Instant.now());
    world.departures.put(created.id(), created);
    return created;
  }

  private Vehicle vehicle(int n, int capacity, UUID tenant) {
    Vehicle v = new Vehicle(new UUID(0, 2000 + n), tenant, "Vehicle " + n, capacity, ACCOUNT, Instant.now());
    world.vehicleRows.put(v.id(), v);
    return v;
  }

  private static String token(String role, UUID tenant, String subject) {
    return Tokens.valid(Tokens.claims(subject, 600, List.of(role), tenant == null ? null : tenant.toString()));
  }

  private static String admin() {
    return token("TENANT_ADMINISTRATOR", TENANT, ACCOUNT.toString());
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

  private void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("message")).isNotBlank();
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
    assertThat(world.assignments).isEmpty();
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_adds_vehicles_and_answers_the_effective_capacity_the_vehicles_and_the_event() {
    Vehicle van = vehicle(1, 12, TENANT);
    Vehicle bus = vehicle(2, 30, TENANT);
    Response response = as(admin()).filter(Contract.filter()).body(ids(van.id(), bus.id())).post(ROUTE, "SAL-001");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getInt("capacity")).isEqualTo(18);
    assertThat(response.jsonPath().getInt("tourCapacity")).isEqualTo(18);
    assertThat(response.jsonPath().getInt("vehicleCapacity")).isEqualTo(42);
    assertThat(response.jsonPath().getBoolean("transportRequired")).isTrue();
    assertThat(response.jsonPath().getList("vehicles.name")).containsExactlyInAnyOrder("Vehicle 1", "Vehicle 2");
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaVehiclesChanged");
      assertThat(event.payload()).containsEntry("vehicleTotalCapacity", 42);
      assertThat(event.correlationId()).isEqualTo(response.header("X-Correlation-Id"));
    });
  }

  @Test
  void C_the_effective_capacity_is_the_vehicles_when_they_are_fewer_seats_than_the_tour() {
    Vehicle van = vehicle(1, 12, TENANT);
    Response response = as(admin()).filter(Contract.filter()).body(ids(van.id())).post(ROUTE, "SAL-001");
    assertThat(response.jsonPath().getInt("capacity")).isEqualTo(12);
  }

  @Test
  void C_adding_a_vehicle_already_there_answers_200_and_writes_nothing_more() {
    Vehicle van = vehicle(1, 12, TENANT);
    as(admin()).filter(Contract.filter()).body(ids(van.id())).post(ROUTE, "SAL-001");
    Response again = as(admin()).filter(Contract.filter()).body(ids(van.id())).post(ROUTE, "SAL-001");
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.jsonPath().getList("vehicles")).hasSize(1);
    assertThat(world.events).hasSize(1);
  }

  @Test
  void C_an_overlapping_vehicle_is_422_naming_the_other_departure() {
    Vehicle van = vehicle(1, 12, TENANT);
    Departure other = departure(2, START.plus(Duration.ofHours(2)), END.plus(Duration.ofHours(2)), DepartureStatus.SCHEDULED);
    world.assignments.put(other.id(), new LinkedHashSet<>(Set.of(van.id())));
    Response response = as(admin()).filter(Contract.answers()).body(ids(van.id())).post(ROUTE, "SAL-001");
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.jsonPath().getString("error")).isEqualTo("BUSINESS_RULE_VIOLATION");
    assertThat(response.jsonPath().getString("message")).contains("SAL-002");
    assertThat(world.assignments.get(departure.id())).isNull();
    assertThat(world.events).isEmpty();
  }

  @Test
  void C_an_unknown_vehicle_or_one_of_another_tenant_is_422() {
    Vehicle foreign = vehicle(1, 12, OTHER_TENANT);
    for (UUID id : List.of(foreign.id(), new UUID(0, 9999))) {
      assertError(as(admin()).filter(Contract.answers()).body(ids(id)).post(ROUTE, "SAL-001"), 422, "BUSINESS_RULE_VIOLATION");
    }
  }

  @Test
  void C_a_tour_without_transport_takes_no_vehicles_422() {
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, false));
    Vehicle van = vehicle(1, 12, TENANT);
    assertError(as(admin()).filter(Contract.answers()).body(ids(van.id())).post(ROUTE, "SAL-001"), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test
  void C_a_departure_that_is_not_scheduled_is_422_invalid_status_transition() {
    Vehicle van = vehicle(1, 12, TENANT);
    departure(2, START, END, DepartureStatus.IN_EXECUTION);
    assertError(as(admin()).filter(Contract.answers()).body(ids(van.id())).post(ROUTE, "SAL-002"), 422, "INVALID_STATUS_TRANSITION");
  }

  @Test
  void C_an_unknown_departure_or_one_of_another_tenant_is_404() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertError(as(admin()).filter(Contract.answers()).body(ids(van.id())).post(ROUTE, "SAL-099"), 404, "NOT_FOUND");
    String other = token("TENANT_ADMINISTRATOR", OTHER_TENANT, ACCOUNT.toString());
    assertError(as(other).filter(Contract.answers()).body(ids(van.id())).post(ROUTE, "SAL-001"), 404, "NOT_FOUND");
  }

  @Test
  void C_a_bad_code_or_bad_ids_are_400() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertError(as(admin()).filter(Contract.answers()).body(ids(van.id())).post(ROUTE, "sal-1"), 400, "VALIDATION_ERROR");
    assertError(as(admin()).filter(Contract.answers()).body(Map.of("vehicleIds", List.of())).post(ROUTE, "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(admin()).filter(Contract.answers()).body(ids(van.id(), van.id())).post(ROUTE, "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(admin()).filter(Contract.answers()).body(Map.of("vehicleIds", List.of("not-a-uuid"))).post(ROUTE, "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(admin()).filter(Contract.answers()).body(Map.of()).post(ROUTE, "SAL-001"), 400, "VALIDATION_ERROR");
    List<String> tooMany = IntStream.range(0, 21).mapToObj(n -> new UUID(0, 5000 + n).toString()).toList();
    assertError(as(admin()).filter(Contract.answers()).body(Map.of("vehicleIds", tooMany)).post(ROUTE, "SAL-001"), 400, "VALIDATION_ERROR");
    assertError(as(admin()).filter(Contract.answers()).body(Map.of("vehicleIds", List.of(van.id().toString()), "tenantId", TENANT.toString()))
        .post(ROUTE, "SAL-001"), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_no_token_is_401_and_only_tenant_administrator_is_allowed() {
    Vehicle van = vehicle(1, 12, TENANT);
    assertError(as(null).filter(Contract.answers()).body(ids(van.id())).post(ROUTE, "SAL-001"), 401, "UNAUTHORIZED");
    for (String role : List.of("OPERATIONAL_COLLABORATOR", "MANAGER", "ACCOUNTANT", "ANALYST", "FINAL_CUSTOMER")) {
      assertError(as(token(role, TENANT, ACCOUNT.toString())).filter(Contract.answers()).body(ids(van.id())).post(ROUTE, "SAL-001"),
          403, "FORBIDDEN");
    }
  }

  @Test
  void C_a_catalog_that_does_not_answer_is_503_and_writes_nothing() {
    Vehicle van = vehicle(1, 12, TENANT);
    world.catalogDown = true;
    assertError(as(admin()).filter(Contract.answers()).body(ids(van.id())).post(ROUTE, "SAL-001"), 503, "SERVICE_UNAVAILABLE");
  }

  // ---- the vehicles of POST /departures
  private static Map<String, Object> scheduleBody(Object... vehicleIds) {
    return Map.of("serviceId", TOUR.toString(), "scheduledStart", START.plus(Duration.ofDays(5)).toString(),
        "scheduledEnd", START.plus(Duration.ofDays(5)).plus(Duration.ofHours(8)).toString(), "timeZone", "America/Bogota",
        "vehicleIds", List.of(vehicleIds).stream().map(Object::toString).toList());
  }

  @Test
  void C_schedules_a_departure_with_its_vehicles_and_answers_them() {
    Vehicle van = vehicle(1, 12, TENANT);
    Response response = as(admin()).header("Idempotency-Key", "key-0001-abcdef").filter(Contract.filter())
        .body(scheduleBody(van.id())).post(DEPARTURES);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.jsonPath().getInt("capacity")).isEqualTo(12);
    assertThat(response.jsonPath().getInt("vehicleCapacity")).isEqualTo(12);
    assertThat(response.jsonPath().getList("vehicles.vehicleId")).containsExactly(van.id().toString());
    assertThat(world.events).extracting(e -> e.eventType()).containsExactly("SalidaScheduled", "SalidaVehiclesChanged");
  }

  @Test
  void C_scheduling_with_a_vehicle_that_overlaps_is_422_and_nothing_is_written() {
    Vehicle van = vehicle(1, 12, TENANT);
    world.assignments.put(departure.id(), new LinkedHashSet<>(Set.of(van.id())));
    Map<String, Object> body = Map.of("serviceId", TOUR.toString(), "scheduledStart", START.plus(Duration.ofHours(1)).toString(),
        "scheduledEnd", END.toString(), "timeZone", "America/Bogota", "vehicleIds", List.of(van.id().toString()));
    Response response = as(admin()).header("Idempotency-Key", "key-0001-abcdef").filter(Contract.answers()).body(body).post(DEPARTURES);
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(world.departures).containsOnlyKeys(departure.id());
    assertThat(world.events).isEmpty();
  }
}
