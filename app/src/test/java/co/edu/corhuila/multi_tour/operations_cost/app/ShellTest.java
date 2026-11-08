package co.edu.corhuila.multi_tour.operations_cost.app;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The service on a random port. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
abstract class ShellTest {

  @LocalServerPort int port;

  @BeforeEach
  void aim() {
    RestAssured.port = port;
  }
}
