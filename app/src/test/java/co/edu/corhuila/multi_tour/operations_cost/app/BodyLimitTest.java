package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** A body is read up to 16 KB; one byte more is refused with the envelope before anyone parses it. */
class BodyLimitTest extends ShellTest {

  private static final int LIMIT = 16 * 1024;
  private static final String ECHO = "/probe/echo";

  /** A JSON object of exactly {@code bytes} bytes. */
  private static byte[] json(int bytes) {
    String head = "{\"x\":\"";
    String tail = "\"}";
    return (head + "a".repeat(bytes - head.length() - tail.length()) + tail).getBytes(StandardCharsets.UTF_8);
  }

  private static Response post(byte[] body) {
    return given().contentType(ContentType.JSON).body(body).post(ECHO);
  }

  private static Response post(String body) {
    return given().contentType(ContentType.JSON).body(body).post(ECHO);
  }

  /** No length is declared: the client sends the stream in chunks. */
  private static Response post(java.io.InputStream body) {
    return given().contentType(ContentType.JSON).body(body).post(ECHO);
  }

  private static void assertTooLarge(Response response) {
    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.jsonPath().getString("error")).isEqualTo("PAYLOAD_TOO_LARGE");
    assertThat(response.jsonPath().getString("traceId")).isEqualTo(response.header("X-Correlation-Id"));
  }

  @Test
  void C_a_body_of_exactly_16_kb_is_read() {
    Response response = post(json(LIMIT));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("x")).hasSize(LIMIT - 8);
  }

  @Test
  void C_one_byte_more_is_a_413_with_the_envelope() {
    assertTooLarge(post(json(LIMIT + 1)));
  }

  @Test
  void C_a_body_sent_in_chunks_is_judged_by_what_arrives_and_not_by_a_length_it_did_not_declare() {
    assertThat(post(new ByteArrayInputStream(json(LIMIT))).statusCode()).isEqualTo(200);
    assertTooLarge(post(new ByteArrayInputStream(json(LIMIT + 1))));
  }

  @Test
  void C_a_caller_without_a_token_is_told_so_before_the_size_of_its_body_is_judged() {
    Response response = given().contentType(ContentType.JSON).body(json(LIMIT + 1)).post("/api/v1/operations-cost/departures");
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void C_a_small_body_and_a_request_without_body_are_not_touched() {
    assertThat(post("{\"x\":1}").statusCode()).isEqualTo(200);
    assertThat(given().get("/health").statusCode()).isEqualTo(200);
  }
}
