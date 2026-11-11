package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class JwtFilterTest extends ShellTest {

  private static final String TENANT = "00000000-0000-4000-8000-0000000000aa";
  private static final List<String> ADMIN = List.of("TENANT_ADMINISTRATOR");
  /** A protected route that is not built, so passing the filter shows as the 404 of an unknown route. */
  private static final String PROTECTED = "/api/v1/operations-cost/not-built";

  private Response call(String authorization) {
    var request = given();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return request.get(PROTECTED);
  }

  private void assertRefused(String authorization) {
    Response response = call(authorization);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.jsonPath().getString("error")).isEqualTo("UNAUTHORIZED");
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
    assertThat(response.header("WWW-Authenticate")).startsWith("Bearer");
  }

  private static JWTClaimsSet.Builder claims(String subject, long seconds, List<String> roles, String tenant) {
    return Tokens.claims(subject, seconds, roles, tenant);
  }

  private static String bearer(JWTClaimsSet.Builder claims) {
    return "Bearer " + Tokens.valid(claims);
  }

  @Test
  void C_no_token_is_401() {
    assertRefused(null);
    assertRefused("Basic dXNlcjpwYXNz");
    assertRefused("Bearer ");
    assertRefused("Bearer abc.def.ghi");
  }

  @Test
  void C_a_valid_token_passes_the_filter() {
    assertThat(call(bearer(claims("account-1", 600, ADMIN, TENANT))).statusCode()).isEqualTo(404);
    assertThat(call("bearer " + Tokens.valid(claims("account-1", 600, ADMIN, TENANT))).statusCode()).isEqualTo(404);
  }

  @Test
  void C_alg_none_hs256_other_key_are_401() {
    JWTClaimsSet claims = claims("account-1", 600, ADMIN, TENANT).build();
    assertRefused("Bearer " + Tokens.unsigned(claims));
    assertRefused("Bearer " + Tokens.hs256WithThePublicKey(claims));
    assertRefused("Bearer " + Tokens.rs256(Tokens.OTHER.getPrivate(), claims));
  }

  @Test
  void C_missing_sub_or_exp_is_401() {
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).subject(null)));
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).expirationTime(null)));
  }

  @Test
  void C_expired_beyond_tolerance_is_401_and_inside_it_passes() {
    assertRefused(bearer(claims("account-1", -31, ADMIN, TENANT)));
    assertThat(call(bearer(claims("account-1", -25, ADMIN, TENANT))).statusCode()).isEqualTo(404);
  }

  @Test
  void C_a_token_not_valid_yet_beyond_the_tolerance_is_401_and_inside_it_passes() {
    long now = System.currentTimeMillis();
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).notBeforeTime(new Date(now + 120_000))));
    assertThat(call(bearer(claims("account-1", 600, ADMIN, TENANT).notBeforeTime(new Date(now + 10_000)))).statusCode())
        .isEqualTo(404);
  }

  @Test
  void C_roles_that_make_no_sense_are_401() {
    assertRefused(bearer(claims("account-1", 600, List.of(), TENANT)));
    assertRefused(bearer(claims("account-1", 600, List.of("EVERYTHING"), TENANT)));
    assertRefused(bearer(claims("account-1", 600, List.of("tenant_administrator"), TENANT)));
    // a role of a tenant without the tenant, and the platform with one
    assertRefused(bearer(claims("account-1", 600, ADMIN, null)));
    assertRefused(bearer(claims("account-1", 600, List.of("PLATFORM_ADMINISTRATOR"), TENANT)));
    // the platform or a service mixed with a role of a tenant
    assertRefused(bearer(claims("account-1", 600, List.of("SERVICE", "MANAGER"), null)));
  }

  @Test
  void C_a_signed_token_with_odd_roles_or_subject_is_401_and_never_a_server_error() {
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).claim("roles", Arrays.asList((String) null))));
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).claim("roles", "TENANT_ADMINISTRATOR")));
    assertRefused(bearer(claims("  ", 600, ADMIN, TENANT)));
  }

  @Test
  void C_a_subject_blank_padded_too_long_or_with_a_control_character_is_401_and_100_characters_pass() {
    for (String bad : List.of("", " ", " account-1", "account-1 ", "a".repeat(101), "acc\u0007ount", "acc‮ount")) {
      assertRefused(bearer(claims(bad, 600, ADMIN, TENANT)));
    }
    assertThat(call(bearer(claims("a".repeat(100), 600, ADMIN, TENANT))).statusCode()).isEqualTo(404);
  }

  @Test
  void C_the_token_of_a_temporary_credential_or_an_unknown_access_mark_is_401() {
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).claim("access", "password-change")));
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).claim("access", "Password-Change")));
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).claim("access", 7)));
    assertThat(call(bearer(claims("account-1", 600, ADMIN, TENANT).claim("access", "restricted"))).statusCode())
        .isEqualTo(404);
  }

  @Test
  void C_the_health_route_needs_no_token() {
    assertThat(given().get("/health").statusCode()).isEqualTo(200);
  }

  @Test
  void C_tenant_in_header_or_body_is_ignored() {
    Response response =
        given()
            .header("Authorization", bearer(claims("account-1", 600, ADMIN, TENANT)))
            .header("X-Tenant-Id", "00000000-0000-4000-8000-0000000000bb")
            .get("/api/v1/operations-cost/probe/whoami?tenantId=00000000-0000-4000-8000-0000000000cc");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("tenantId")).isEqualTo(TENANT);
    assertThat(response.jsonPath().getString("subject")).isEqualTo("account-1");
  }

  @Test
  void C_the_permissions_claim_reaches_the_caller_and_its_absence_is_none() {
    Response with =
        given()
            .header("Authorization", bearer(claims("account-1", 600, ADMIN, TENANT).claim("permissions", List.of("MANAGE_CASH", "MANAGE_DEPARTURE_EXECUTION"))))
            .get("/api/v1/operations-cost/probe/whoami");
    assertThat(with.jsonPath().getString("permissions")).isEqualTo("[MANAGE_CASH, MANAGE_DEPARTURE_EXECUTION]");
    Response without = given().header("Authorization", bearer(claims("account-1", 600, ADMIN, TENANT))).get("/api/v1/operations-cost/probe/whoami");
    assertThat(without.jsonPath().getString("permissions")).isEqualTo("[]");
  }

  @Test
  void C_a_permissions_claim_that_is_not_a_list_of_strings_is_a_refusal() {
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).claim("permissions", "MANAGE_DEPARTURE_EXECUTION")));
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).claim("permissions", List.of(1, 2))));
  }

  private static final String PLATFORM = "PLATFORM_ADMINISTRATOR";
  private static final String AUTHORIZATION_ID = "3f2b8c1e-1111-4222-8333-444455556666";

  private static JWTClaimsSet.Builder exceptional(String access, List<String> scope) {
    return claims("platform-admin-1", 600, List.of(PLATFORM), TENANT).claim("access", access).claim("scope", scope).claim("authorizationId", AUTHORIZATION_ID);
  }

  @Test
  void C_the_exceptional_and_the_emergency_token_pass_the_filter_and_read() {
    for (String access : List.of("exceptional", "emergency")) {
      Response response = given().header("Authorization", bearer(exceptional(access, List.of("OPERATIONS", "SENSITIVE")))).get("/api/v1/operations-cost/probe/whoami");
      assertThat(response.statusCode()).as(access).isEqualTo(200);
      assertThat(response.jsonPath().getString("tenantId")).isEqualTo(TENANT);
    }
  }

  @Test
  void C_a_write_with_the_token_is_forbidden_and_a_read_outside_the_scope_is_forbidden() {
    assertThat(given().header("Authorization", bearer(exceptional("exceptional", List.of("OPERATIONS")))).post("/api/v1/operations-cost/vehicles").statusCode()).isEqualTo(403);
    assertThat(given().header("Authorization", bearer(exceptional("exceptional", List.of("CASH")))).get("/api/v1/operations-cost/probe/whoami").statusCode()).isEqualTo(403);
    assertThat(given().header("Authorization", bearer(exceptional("exceptional", List.of("OPERATIONS")))).get("/api/v1/operations-cost/internal/outbox/events").statusCode()).isEqualTo(403);
  }

  @Test
  void C_a_platform_token_without_the_mark_is_forbidden() {
    assertThat(given().header("Authorization", bearer(claims("platform-admin-1", 600, List.of(PLATFORM), TENANT))).get("/api/v1/operations-cost/probe/whoami").statusCode()).isEqualTo(401);
    assertThat(given().header("Authorization", bearer(claims("platform-admin-1", 600, List.of(PLATFORM), null))).get("/api/v1/operations-cost/probe/whoami").statusCode()).isEqualTo(403);
  }

  @Test
  void A_a_token_with_access_and_no_authorization_id_is_refused_and_so_is_an_unknown_scope_word() {
    assertRefused(bearer(exceptional("exceptional", List.of("OPERATIONS")).claim("authorizationId", null)));
    assertRefused(bearer(exceptional("exceptional", List.of("OPERATIONS")).claim("authorizationId", "not-a-uuid")));
    assertRefused(bearer(exceptional("exceptional", List.of("OPERATIONS", "EVERYTHING"))));
    assertRefused(bearer(exceptional("exceptional", List.of())));
    assertRefused(bearer(exceptional("exceptional", List.of("OPERATIONS")).claim("scope", "OPERATIONS")));
    // the mark narrows only the Platform Administrator for one tenant: any other shape is refused
    assertRefused(bearer(claims("account-1", 600, ADMIN, TENANT).claim("access", "exceptional").claim("scope", List.of("OPERATIONS")).claim("authorizationId", AUTHORIZATION_ID)));
    assertRefused(bearer(claims("platform-admin-1", 600, List.of(PLATFORM), null).claim("access", "exceptional").claim("scope", List.of("OPERATIONS")).claim("authorizationId", AUTHORIZATION_ID)));
  }

  @Test
  void C_start_fails_without_the_key() {
    assertThatThrownBy(() -> start(Map.of())).hasStackTraceContaining("JWT_PUBLIC_KEY");
  }

  @Test
  void C_the_service_starts_with_the_key_in_a_file() throws IOException {
    Path file = Files.createTempFile("jwt", ".pem");
    try {
      Files.writeString(file, Tokens.publicKeyPem().replace("\\n", "\n"));
      try (ConfigurableApplicationContext context = start(Map.of("JWT_PUBLIC_KEY_FILE", file.toString()))) {
        assertThat(context.isRunning()).isTrue();
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void C_start_fails_with_a_key_shorter_than_2048_bits() throws Exception {
    java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
    generator.initialize(1024);
    String body = java.util.Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    Path file = Files.createTempFile("short", ".pem");
    try {
      Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
      assertThatThrownBy(() -> start(Map.of("JWT_PUBLIC_KEY_FILE", file.toString()))).hasStackTraceContaining("2048");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static ConfigurableApplicationContext start(Map<String, String> properties) {
    // the blank key comes first so a variable of the machine cannot supply it; later arguments win
    List<String> args = new ArrayList<>(List.of("--server.port=0", "--JWT_PUBLIC_KEY=", "--DATABASE_URL=jdbc:postgresql://localhost:5432/unused",
        "--COMMERCIAL_RESERVATION_BASE_URL=http://localhost:8083"));
    properties.forEach((name, value) -> args.add("--" + name + "=" + value));
    return SpringApplication.run(CompositionRoot.class, args.toArray(String[]::new));
  }
}
