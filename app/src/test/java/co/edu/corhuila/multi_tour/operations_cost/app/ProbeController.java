package co.edu.corhuila.multi_tour.operations_cost.app;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Routes that exist only in the tests, to make the shell fail in the ways a real route will. */
@RestController
class ProbeController {

  @PostMapping(path = "/probe/echo", consumes = "application/json")
  Map<String, Object> echo(@RequestBody Map<String, Object> body) {
    return body;
  }

  @GetMapping("/probe/number")
  Map<String, Integer> number(@RequestParam int n) {
    return Map.of("n", n);
  }

  @GetMapping("/probe/boom")
  void boom() {
    throw new IllegalStateException("jdbc:postgresql://operations_cost_app:secret@postgres:5432");
  }
}
