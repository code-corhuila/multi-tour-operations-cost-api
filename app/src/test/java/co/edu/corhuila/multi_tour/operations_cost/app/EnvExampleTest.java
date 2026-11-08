package co.edu.corhuila.multi_tour.operations_cost.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The example of the environment names every variable the service reads and holds no key and no secret. */
class EnvExampleTest {

  private static List<String> lines() throws IOException {
    // the tests of this module run from its folder; the example is at the root of the repository
    return Files.readAllLines(Path.of("..", ".env.example"));
  }

  @Test
  void C_the_example_names_the_variables_the_service_reads() throws IOException {
    String text = String.join("\n", lines());
    assertThat(text).contains("JWT_PUBLIC_KEY=", "JWT_PUBLIC_KEY_FILE=", "DATABASE_URL=", "DATABASE_USER=", "DATABASE_PASSWORD=");
  }

  @Test
  void C_the_example_holds_no_key_and_no_secret() throws IOException {
    assertThat(lines()).filteredOn(line -> line.startsWith("JWT_PUBLIC_KEY=")).containsExactly("JWT_PUBLIC_KEY=");
    assertThat(lines()).filteredOn(line -> line.startsWith("DATABASE_PASSWORD=")).containsExactly("DATABASE_PASSWORD=");
    assertThat(String.join("\n", lines())).doesNotContain("BEGIN");
  }
}
