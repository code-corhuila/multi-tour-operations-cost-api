package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HttpShellTest extends ShellTest {

  /** The single envelope: only its four fields, a real message and the trace id the caller can quote. */
  private static void assertEnvelope(Response response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.contentType()).startsWith("application/json");
    assertThat(response.jsonPath().getMap("$").keySet())
        .isSubsetOf(Set.of("error", "message", "details", "traceId"))
        .contains("error", "message", "traceId");
    assertThat(response.jsonPath().getString("error")).isEqualTo(code);
    assertThat(response.jsonPath().getString("message")).isNotBlank();
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_health_answers_without_a_token_and_matches_the_contract() {
    given().filter(Contract.filter()).get("/health").then().statusCode(200).body("status", equalTo("ok"));
  }

  @Test
  void C_unknown_route_answers_the_envelope() {
    assertEnvelope(given().get("/nowhere"), 404, "NOT_FOUND");
  }

  @Test
  void C_malformed_json_answers_the_envelope() {
    assertEnvelope(
        given().contentType("application/json").body("{not json").post("/probe/echo"),
        400,
        "VALIDATION_ERROR");
  }

  @Test
  void C_a_bad_parameter_is_a_400_and_not_a_server_error() {
    assertEnvelope(given().get("/probe/number?n=abc"), 400, "VALIDATION_ERROR");
  }

  @Test
  void C_wrong_method_is_a_405_that_keeps_the_allow_header() {
    Response response = given().post("/health");
    assertEnvelope(response, 405, "METHOD_NOT_ALLOWED");
    assertThat(response.header("Allow")).contains("GET");
  }

  @Test
  void C_unsupported_media_type_is_a_415() {
    assertEnvelope(
        given().contentType("text/plain").body("x").post("/probe/echo"), 415, "UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void C_unacceptable_media_type_is_a_406_with_a_readable_envelope() {
    assertEnvelope(given().accept("text/xml").get("/health"), 406, "NOT_ACCEPTABLE");
  }

  @Test
  void C_unexpected_failure_hides_the_detail_and_keeps_the_trace() {
    Response response = given().get("/probe/boom");
    assertEnvelope(response, 500, "INTERNAL_ERROR");
    assertThat(response.asString()).doesNotContain("postgresql").doesNotContain("secret");
  }

  @Test
  void C_failure_inside_a_filter_answers_the_envelope_with_the_same_trace_id() {
    Response response =
        given().header("X-Correlation-Id", "boom-trace").header("X-Boom-Filter", "1").get("/health");
    assertEnvelope(response, 500, "INTERNAL_ERROR");
    assertThat(response.header("X-Correlation-Id")).isEqualTo("boom-trace");
    assertThat(response.asString()).doesNotContain("boom secret");
  }

  @Test
  void C_a_direct_request_to_the_error_page_is_a_404_and_not_a_server_error() {
    assertEnvelope(given().get("/error"), 404, "NOT_FOUND");
  }

  @Test
  void C_valid_correlation_id_is_reused_and_is_the_trace_id() {
    Response response = given().header("X-Correlation-Id", "run-42.a_b").get("/nowhere");
    assertThat(response.header("X-Correlation-Id")).isEqualTo("run-42.a_b");
    assertThat(response.jsonPath().getString("traceId")).isEqualTo("run-42.a_b");
  }

  @Test
  void C_a_correlation_id_of_128_characters_is_the_longest_that_is_reused() {
    String longest = "x".repeat(128);
    assertThat(given().header("X-Correlation-Id", longest).get("/nowhere").header("X-Correlation-Id"))
        .isEqualTo(longest);
  }

  @Test
  void C_missing_or_invalid_correlation_id_is_replaced_by_a_new_one() {
    for (String sent : new String[] {null, "", "has space", "semi;colon", "x".repeat(129)}) {
      RequestSpecification request = given();
      if (sent != null) {
        request.header("X-Correlation-Id", sent);
      }
      Response response = request.get("/nowhere");
      assertThat(response.header("X-Correlation-Id")).isNotBlank().isNotEqualTo(sent);
      assertThat(response.jsonPath().getString("traceId"))
          .isEqualTo(response.header("X-Correlation-Id"));
    }
  }

  @Test
  void C_each_request_gets_its_own_id_and_none_is_left_behind() {
    String first = given().header("X-Correlation-Id", "first-run").get("/health").header("X-Correlation-Id");
    String second = given().get("/health").header("X-Correlation-Id");
    assertThat(first).isEqualTo("first-run");
    assertThat(second).isNotBlank().isNotEqualTo(first);
  }
}
