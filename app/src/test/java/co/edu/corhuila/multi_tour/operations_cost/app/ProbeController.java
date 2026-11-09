package co.edu.corhuila.multi_tour.operations_cost.app;

import co.edu.corhuila.multi_tour.operations_cost.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
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

  /** A protected route that says who the filter decided the caller is: the tenant is the token's and no other. */
  @GetMapping("/api/v1/operations-cost/probe/whoami")
  Map<String, String> whoami(HttpServletRequest request) {
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    return Map.of("subject", caller.subject(), "tenantId", String.valueOf(caller.tenantId()),
        "permissions", caller.permissions().stream().sorted().toList().toString());
  }
}
