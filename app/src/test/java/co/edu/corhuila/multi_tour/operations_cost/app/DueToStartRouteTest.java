package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** The internal route job 3 calls to find the due departures of an inactive tenant (job 3 spec 002, AC-API-14.1 and -14.2), validated against the contract. */
@Import(InMemoryWorld.Config.class)
class DueToStartRouteTest extends ShellTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private static final String ROUTE = "/api/v1/operations-cost/internal/tenants/" + TENANT + "/departures/due-to-start";

  private final InMemoryWorld world = InMemoryWorld.WORLD;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    put(TENANT, 1, NOW.minus(Duration.ofHours(3)));
    put(TENANT, 2, NOW.minus(Duration.ofHours(5)));
    put(TENANT, 3, NOW.plus(Duration.ofHours(5)));
    Departure running = put(TENANT, 4, NOW.minus(Duration.ofHours(6)));
    world.departures.put(running.id(), running.start(NOW.minus(Duration.ofHours(6)), ACCOUNT, NOW));
    put(OTHER, 5, NOW.minus(Duration.ofHours(7)));
  }

  private Departure put(UUID tenant, int n, Instant start) {
    Departure created = new Departure(new UUID(0, 2000 + n), tenant, String.format("SAL-%03d", n), TOUR, start, start.plus(Duration.ofHours(8)),
        "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, start.minus(Duration.ofDays(3)));
    world.departures.put(created.id(), created);
    return created;
  }

  private static String service() {
    return Tokens.valid(Tokens.claims("worker-departure-deadlines", 600, List.of("SERVICE"), null));
  }

  private static String administrator() {
    return Tokens.valid(Tokens.claims(ACCOUNT.toString(), 600, List.of("TENANT_ADMINISTRATOR"), TENANT.toString())
        .claim("permissions", List.of("MANAGE_DEPARTURE_EXECUTION")));
  }

  private static RequestSpecification as(String bearer) {
    RequestSpecification request = given();
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return request;
  }

  private static void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_due_to_start_lists_only_scheduled_departures_whose_start_passed() {
    Response response = as(service()).filter(Contract.filter()).get(ROUTE);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getList("data.departureCode")).containsExactly("SAL-002", "SAL-001");
    Response bounded = as(service()).filter(Contract.filter()).queryParam("limit", 1).get(ROUTE);
    assertThat(bounded.jsonPath().getList("data.departureCode")).containsExactly("SAL-002");
    assertThat(world.departures.values()).filteredOn(d -> d.tenantId().equals(TENANT) && d.status() == DepartureStatus.SCHEDULED).hasSize(3);
  }

  @Test
  void C_a_person_cannot_list_the_due_departures() {
    assertError(as(administrator()).filter(Contract.filter()).get(ROUTE), 403, "FORBIDDEN");
  }

  @Test
  void C_without_a_token_the_due_departures_are_401() {
    assertError(as(null).filter(Contract.filter()).get(ROUTE), 401, "UNAUTHORIZED");
  }

  @Test
  void C_a_limit_out_of_range_or_an_unknown_parameter_is_400() {
    assertError(as(service()).filter(Contract.answers()).queryParam("limit", 0).get(ROUTE), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).queryParam("limit", 101).get(ROUTE), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).queryParam("limit", "many").get(ROUTE), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).queryParam("status", "x").get(ROUTE), 400, "VALIDATION_ERROR");
  }
}
