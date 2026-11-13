package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness, with no token. The look at the database is added with the persistence (spec 002, task S2-03). */
@RestController
class HealthController {

  public record Health(String status, Instant timestamp) {}

  private final Clock clock;

  HealthController(Clock clock) {
    this.clock = clock;
  }

  @GetMapping("/health")
  Health health() {
    return new Health("ok", clock.instant());
  }
}
