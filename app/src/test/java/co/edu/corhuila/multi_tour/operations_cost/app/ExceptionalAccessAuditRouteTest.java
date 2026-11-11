package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import io.restassured.http.ContentType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** Spec 012 RN-SEC-012-07: each read the token is let through leaves one fact in the outbox; a refused call leaves none. */
@Import(InMemoryWorld.Config.class)
class ExceptionalAccessAuditRouteTest extends ShellTest {

  private static final String TENANT = "00000000-0000-4000-8000-0000000000aa";
  private static final String AUTHORIZATION_ID = "3f2b8c1e-1111-4222-8333-444455556666";
  private static final String ACCOUNT = "00000000-0000-4000-8000-0000000000cc";

  private final InMemoryWorld world = InMemoryWorld.WORLD;

  @BeforeEach
  void setUp() {
    world.reset();
  }

  private static String token(String access, List<String> scope) {
    return Tokens.valid(Tokens.claims(ACCOUNT, 600, List.of("PLATFORM_ADMINISTRATOR"), TENANT)
        .claim("access", access).claim("scope", scope).claim("authorizationId", AUTHORIZATION_ID));
  }

  private static io.restassured.specification.RequestSpecification as(String bearer) {
    return given().header("Authorization", "Bearer " + bearer).header("X-Correlation-Id", "trace-spec-012");
  }

  @Test
  void A_a_use_writes_its_fact_with_no_data() {
    assertThat(as(token("exceptional", List.of("OPERATIONS"))).get("/api/v1/operations-cost/vehicles?limit=5").statusCode()).isEqualTo(200);

    assertThat(world.events).hasSize(1);
    OutboxEvent fact = world.events.get(0);
    assertThat(fact.eventType()).isEqualTo("PlatformExceptionalAccessUsed");
    assertThat(fact.payload()).containsOnlyKeys(
        "authorizationId", "platformAdministratorId", "tenantId", "method", "route", "area", "usedAt", "correlationId");
    assertThat(fact.payload()).containsEntry("authorizationId", AUTHORIZATION_ID).containsEntry("platformAdministratorId", ACCOUNT)
        .containsEntry("tenantId", TENANT).containsEntry("method", "GET").containsEntry("area", "OPERATIONS")
        .containsEntry("route", "/api/v1/operations-cost/vehicles").containsEntry("correlationId", "trace-spec-012");
    // the query string is never part of the fact
    assertThat(fact.payload().toString()).doesNotContain("limit");
  }

  @Test
  void C_a_refused_call_writes_no_fact() {
    assertThat(as(token("exceptional", List.of("OPERATIONS"))).contentType(ContentType.JSON).body("{}").post("/api/v1/operations-cost/vehicles").statusCode()).isEqualTo(403);
    assertThat(as(token("exceptional", List.of("CASH"))).get("/api/v1/operations-cost/vehicles").statusCode()).isEqualTo(403);
    assertThat(as(token("exceptional", List.of("OPERATIONS"))).get("/api/v1/operations-cost/internal/outbox").statusCode()).isEqualTo(403);

    assertThat(world.events).isEmpty();
  }

  @Test
  void C_an_ordinary_token_leaves_no_use_fact() {
    String administrator = Tokens.valid(Tokens.claims(ACCOUNT, 600, List.of("TENANT_ADMINISTRATOR"), TENANT));
    assertThat(as(administrator).get("/api/v1/operations-cost/vehicles").statusCode()).isEqualTo(200);

    assertThat(world.events).isEmpty();
  }
}
