package co.edu.corhuila.multi_tour.operations_cost.app;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.Environment;

@ExtendWith(OutputCaptureExtension.class)
class LogAndLimitsTest extends ShellTest {

  @Autowired Environment environment;
  @Autowired ServletWebServerApplicationContext context;

  /** The line is written after the answer has left, so wait for it instead of reading too early. */
  private static void awaitLine(CapturedOutput output, String marker) {
    await().atMost(Duration.ofSeconds(5)).until(() -> output.getOut().contains(marker));
  }

  @Test
  void C_the_access_line_is_json_and_carries_the_correlation_id_and_the_status(CapturedOutput output) {
    given().header("X-Correlation-Id", "trace-in-log").get("/nowhere").then().statusCode(404);
    awaitLine(output, "GET /nowhere answered 404");
    var lines = output.getOut().lines().filter(line -> line.contains("trace-in-log")).toList();
    assertThat(lines).isNotEmpty().allSatisfy(line -> {
      assertThat(line).startsWith("{").endsWith("}");
      assertThat(line).contains("\"traceId\":\"trace-in-log\"");
    });
    assertThat(lines).anyMatch(line -> line.contains("GET /nowhere answered 404"));
  }

  @Test
  void C_a_request_without_the_header_logs_its_own_id_and_never_a_previous_one(CapturedOutput output) {
    given().header("X-Correlation-Id", "previous-run").get("/health");
    String last = null;
    for (int i = 0; i < 6; i++) { // enough requests to reuse the server threads
      last = given().get("/health").header("X-Correlation-Id");
    }
    awaitLine(output, "\"traceId\":\"" + last + "\"");
    // the id of the first request is on the lines of the first request only: a leak would add more
    assertThat(output.getOut().lines().filter(line -> line.contains("previous-run"))).hasSize(1);
  }

  @Test
  void C_a_very_long_path_is_cut_before_it_reaches_the_log(CapturedOutput output) {
    given().header("X-Correlation-Id", "long-path").get("/" + "a".repeat(400));
    awaitLine(output, "answered 404");
    var lines = output.getOut().lines().filter(line -> line.contains("long-path")).toList();
    assertThat(lines).anyMatch(line -> line.contains("answered 404") && line.contains("..."));
    assertThat(lines).allSatisfy(line -> assertThat(line).doesNotContain("a".repeat(201)));
  }

  @Test
  void C_the_server_applies_the_declared_limits() {
    TomcatWebServer server = (TomcatWebServer) context.getWebServer();
    AbstractHttp11Protocol<?> http =
        (AbstractHttp11Protocol<?>) server.getTomcat().getConnector().getProtocolHandler();
    assertThat(http.getConnectionTimeout()).isEqualTo(5_000);
    assertThat(http.getKeepAliveTimeout()).isEqualTo(60_000);
    assertThat(http.getMaxHttpRequestHeaderSize()).isEqualTo(4_096);
  }

  @Test
  void C_a_header_over_the_declared_size_is_refused_and_one_under_it_is_served() {
    // 4 KB is not Tomcat's default (8 KB), so this fails if the property is not applied
    given().header("X-Padding", "p".repeat(3_000)).get("/health").then().statusCode(200);
    int refused = given().header("X-Padding", "p".repeat(5_000)).get("/health").statusCode();
    assertThat(refused).isIn(400, 431);  }

  @Test
  void C_the_shutdown_is_graceful_for_at_most_twenty_seconds() {
    assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
  }
}
