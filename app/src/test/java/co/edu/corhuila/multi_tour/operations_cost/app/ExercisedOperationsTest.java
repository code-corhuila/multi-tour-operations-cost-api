package co.edu.corhuila.multi_tour.operations_cost.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ExercisedOperationsTest {

  private static final Set<String> ALL = Set.of("GET /health", "POST /tenants", "GET /tenants/{id}");

  @Test
  void C_an_operation_is_exercised_only_when_it_was_answered_with_a_2xx() {
    ExercisedOperations seen = new ExercisedOperations();
    seen.record("GET", "/health", 200);
    seen.record("POST", "/tenants", 401);
    seen.record("POST", "/tenants", 400);
    assertThat(seen.missing(ALL)).containsExactlyInAnyOrder("POST /tenants", "GET /tenants/{id}");
    seen.record("POST", "/tenants", 201);
    assertThat(seen.missing(ALL)).containsExactly("GET /tenants/{id}");
  }

  @Test
  void C_nothing_seen_means_everything_is_missing() {
    assertThat(new ExercisedOperations().missing(ALL)).isEqualTo(ALL);
  }
}
