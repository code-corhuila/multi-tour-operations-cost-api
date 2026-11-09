package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import io.restassured.http.ContentType;
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

/** The internal route job 3 calls to mark the departures past their end (api spec 005): only the SERVICE role, validated against the contract. */
@Import(InMemoryWorld.Config.class)
class MarkPendingRegularizationRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/operations-cost/internal/departures/pending-regularization";
  private static final String DEPARTURES = "/api/v1/operations-cost/departures";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  private final InMemoryWorld world = InMemoryWorld.WORLD;

  @BeforeEach
  void setUp() {
    world.reset();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, true));
    put(1, NOW.minus(Duration.ofHours(3)));
    put(2, NOW.minus(Duration.ofHours(2)));
    put(3, NOW.plus(Duration.ofHours(5)));
  }

  private void put(int n, Instant end) {
    Departure created = new Departure(new UUID(0, 1000 + n), TENANT, String.format("SAL-%03d", n), TOUR, end.minus(Duration.ofHours(8)), end,
        "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, end.minus(Duration.ofDays(3)));
    world.departures.put(created.id(), created);
  }

  private static String service() {
    return Tokens.valid(Tokens.claims("worker-departure-deadlines", 600, List.of("SERVICE"), null));
  }

  private static String administrator() {
    return Tokens.valid(Tokens.claims(ACCOUNT.toString(), 600, List.of("TENANT_ADMINISTRATOR"), TENANT.toString())
        .claim("permissions", List.of("MANAGE_DEPARTURE_EXECUTION")));
  }

  private static RequestSpecification as(String bearer) {
    RequestSpecification request = given().contentType(ContentType.JSON);
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
  void C_the_service_marks_the_departures_past_their_end_and_the_list_shows_the_mark() {
    Response response = as(service()).filter(Contract.filter()).post(ROUTE);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getInt("marked")).isEqualTo(2);
    Response list = as(administrator()).filter(Contract.filter()).get(DEPARTURES);
    assertThat(list.jsonPath().getList("data.code")).containsExactly("SAL-003", "SAL-002", "SAL-001");
    assertThat(list.jsonPath().getList("data.pendingRegularization", Boolean.class)).containsExactly(false, true, true);
    assertThat(list.jsonPath().getList("data.status")).containsOnly("SCHEDULED");
  }

  @Test
  void C_the_same_ask_again_marks_nothing_more() {
    as(service()).filter(Contract.filter()).post(ROUTE);
    assertThat(as(service()).filter(Contract.filter()).post(ROUTE).jsonPath().getInt("marked")).isZero();
  }

  @Test
  void C_the_limit_bounds_the_ask() {
    assertThat(as(service()).filter(Contract.filter()).queryParam("limit", 1).post(ROUTE).jsonPath().getInt("marked")).isEqualTo(1);
  }

  @Test
  void C_a_limit_out_of_range_or_an_unknown_parameter_is_400() {
    assertError(as(service()).filter(Contract.answers()).queryParam("limit", 0).post(ROUTE), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).queryParam("limit", 501).post(ROUTE), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).queryParam("limit", "many").post(ROUTE), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).queryParam("tenant", "x").post(ROUTE), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_without_the_service_role_it_is_403_and_without_a_token_401() {
    assertError(as(administrator()).filter(Contract.filter()).post(ROUTE), 403, "FORBIDDEN");
    assertError(as(null).filter(Contract.filter()).post(ROUTE), 401, "UNAUTHORIZED");
    assertThat(world.departures.values()).noneMatch(d -> d.execution().pendingRegularization());
  }
}
