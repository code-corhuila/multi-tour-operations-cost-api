package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.security.AccountSessionCutoff;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * The revocation of the sessions of an account (HU-SEC-005 AC4, api spec 013): job 7 delivers it to the internal route, and a token of
 * that account issued before the cutoff is refused on every route that asks for one. Every answer of the route is validated against the
 * contract.
 */
@Import(InMemoryWorld.Config.class)
class PlatformEventRouteTest extends ShellTest {

  private static final String ROUTE = "/api/v1/operations-cost/internal/events";
  private static final String PROTECTED = "/api/v1/operations-cost/vehicles";
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID OTHER_ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cd");

  private final InMemoryCutoffs store = InMemoryCutoffs.STORE;

  @BeforeEach
  @AfterEach
  void clean() {
    store.reset();
  }

  private static String service() {
    return Tokens.valid(Tokens.claims("event-relay", 600, List.of("SERVICE"), null));
  }

  /** A Tenant Administrator of the account, with a token issued at {@code issuedAt} (none when null). */
  private static String person(UUID account, Instant issuedAt) {
    var claims = Tokens.claims(account.toString(), 600, List.of("TENANT_ADMINISTRATOR"), TENANT.toString());
    return Tokens.valid(issuedAt == null ? claims : claims.issueTime(Date.from(issuedAt)));
  }

  private static RequestSpecification as(String bearer) {
    RequestSpecification request = given().contentType(ContentType.JSON);
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return request;
  }

  private static String event(UUID eventId, String eventType, UUID aggregateId, UUID accountId, String revokedFrom) {
    return """
        {"eventId":"%s","eventType":"%s","eventVersion":1,"aggregateId":"%s","payload":{"accountId":"%s","revokedFrom":"%s","tenantId":"%s"},
         "occurredAt":"%s","correlationId":"trace-1"}""".formatted(eventId, eventType, aggregateId, accountId, revokedFrom, TENANT, revokedFrom);
  }

  private static String revocation(UUID eventId, Instant revokedFrom) {
    return event(eventId, "AccountSessionsRevoked", ACCOUNT, ACCOUNT, revokedFrom.toString());
  }

  private static Response deliver(UUID eventId, String body) {
    return as(service()).filter(Contract.filter()).header("Idempotency-Key", eventId.toString()).body(body).post(ROUTE);
  }

  private static void assertError(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_a_first_revocation_is_201_and_a_repeat_or_an_older_instant_is_200_and_changes_nothing() {
    Instant from = Instant.parse("2026-11-14T15:00:00Z");
    UUID first = UUID.randomUUID();
    assertThat(deliver(first, revocation(first, from)).statusCode()).isEqualTo(201);
    assertThat(store.cutoffs.get(ACCOUNT)).isEqualTo(new AccountSessionCutoff(ACCOUNT, TENANT, from));

    assertThat(deliver(first, revocation(first, from.plusSeconds(60))).statusCode()).isEqualTo(200);
    UUID older = UUID.randomUUID();
    assertThat(deliver(older, revocation(older, from.minusSeconds(60))).statusCode()).isEqualTo(200);
    assertThat(store.cutoffs.get(ACCOUNT).revokedFrom()).isEqualTo(from);

    UUID later = UUID.randomUUID();
    assertThat(deliver(later, revocation(later, from.plusSeconds(60))).statusCode()).isEqualTo(201);
    assertThat(store.cutoffs.get(ACCOUNT).revokedFrom()).isEqualTo(from.plusSeconds(60));
  }

  @Test
  void C_a_token_issued_before_the_cutoff_is_401_and_one_after_it_passes() {
    Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(30);
    UUID eventId = UUID.randomUUID();
    assertThat(deliver(eventId, revocation(eventId, from)).statusCode()).isEqualTo(201);

    assertError(as(person(ACCOUNT, from.minusSeconds(1))).get(PROTECTED), 401, "UNAUTHORIZED");
    assertError(as(person(ACCOUNT, null)).get(PROTECTED), 401, "UNAUTHORIZED");
    assertThat(as(person(ACCOUNT, from.minusSeconds(1))).get(PROTECTED).header("WWW-Authenticate")).startsWith("Bearer");
    // a sign-in in the second of the revocation is a new session
    assertThat(as(person(ACCOUNT, from.plusMillis(400))).get(PROTECTED).statusCode()).isEqualTo(200);
    assertThat(as(person(ACCOUNT, from.plusSeconds(5))).get(PROTECTED).statusCode()).isEqualTo(200);
    assertThat(as(person(OTHER_ACCOUNT, from.minusSeconds(1))).get(PROTECTED).statusCode()).isEqualTo(200);
  }

  @Test
  void C_a_person_cannot_deliver_a_revocation() {
    UUID eventId = UUID.randomUUID();
    String body = revocation(eventId, Instant.now());
    assertError(as(null).filter(Contract.answers()).header("Idempotency-Key", eventId.toString()).body(body).post(ROUTE), 401, "UNAUTHORIZED");
    for (String role : List.of("TENANT_ADMINISTRATOR", "OPERATIONAL_COLLABORATOR", "FINAL_CUSTOMER", "PLATFORM_ADMINISTRATOR")) {
      String token = Tokens.valid(Tokens.claims(ACCOUNT.toString(), 600, List.of(role), role.equals("PLATFORM_ADMINISTRATOR") ? null : TENANT.toString()));
      assertError(as(token).filter(Contract.answers()).header("Idempotency-Key", eventId.toString()).body(body).post(ROUTE), 403, "FORBIDDEN");
    }
    assertThat(store.cutoffs).isEmpty();
    assertThat(store.processed).isEmpty();
  }

  @Test
  void C_a_cutoff_that_cannot_be_read_is_503() {
    store.down = true;
    Response response = as(person(ACCOUNT, Instant.now())).get(PROTECTED);
    assertError(response, 503, "SERVICE_UNAVAILABLE");
  }

  @Test
  void C_a_revocation_that_breaks_the_contract_is_400_and_keeps_nothing() {
    UUID id = UUID.randomUUID();
    String at = "2026-11-14T15:00:00Z";
    List<String> broken = List.of(
        event(id, "TenantInactivated", ACCOUNT, ACCOUNT, at),
        event(id, "AccountSessionsRevoked", OTHER_ACCOUNT, ACCOUNT, at),
        event(id, "AccountSessionsRevoked", ACCOUNT, ACCOUNT, "yesterday"),
        event(id, "AccountSessionsRevoked", ACCOUNT, ACCOUNT, at).replace("\"eventVersion\":1,", ""),
        event(id, "AccountSessionsRevoked", ACCOUNT, ACCOUNT, at).replace("\"eventVersion\":1,", "\"eventVersion\":0,"),
        event(id, "AccountSessionsRevoked", ACCOUNT, ACCOUNT, at).replace("\"accountId\":\"" + ACCOUNT + "\",", ""),
        event(id, "AccountSessionsRevoked", ACCOUNT, ACCOUNT, at).replace("\"occurredAt\":\"" + at + "\",", ""),
        event(id, "AccountSessionsRevoked", ACCOUNT, ACCOUNT, at).replace("\"tenantId\":\"" + TENANT + "\"", "\"tenantId\":\"none\""),
        "[]",
        "{not json");
    for (String body : broken) {
      assertError(as(service()).filter(Contract.answers()).header("Idempotency-Key", id.toString()).body(body).post(ROUTE), 400, "VALIDATION_ERROR");
    }
    String good = revocation(id, Instant.parse(at));
    assertError(as(service()).filter(Contract.answers()).header("Idempotency-Key", UUID.randomUUID().toString()).body(good).post(ROUTE), 400, "VALIDATION_ERROR");
    assertError(as(service()).filter(Contract.answers()).body(good).post(ROUTE), 400, "VALIDATION_ERROR");
    String large = good.replace("\"correlationId\":\"trace-1\"", "\"correlationId\":\"" + "a".repeat(16 * 1024) + "\"");
    assertError(as(service()).filter(Contract.answers()).header("Idempotency-Key", id.toString()).body(large).post(ROUTE), 413, "PAYLOAD_TOO_LARGE");
    assertThat(store.cutoffs).isEmpty();
    assertThat(store.processed).isEmpty();
  }
}
