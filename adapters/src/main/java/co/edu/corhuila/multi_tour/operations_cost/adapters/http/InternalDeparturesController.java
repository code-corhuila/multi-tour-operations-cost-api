package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.MarkPendingRegularization;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What job 3 asks of the departures (HU-OPS-003 part B). Only the SERVICE role opens these routes ({@code RouteAuthorization}) and the
 * gateway never routes them (ADR-004 D5). The answer is a count and nothing of any tenant.
 */
@RestController
@RequestMapping("/api/v1/operations-cost/internal/departures")
class InternalDeparturesController {

  private static final int DEFAULT_LIMIT = 100;
  private static final Set<String> QUERY = Set.of("limit");

  record Marked(int marked) {}

  private final MarkPendingRegularization markPendingRegularization;

  InternalDeparturesController(MarkPendingRegularization markPendingRegularization) {
    this.markPendingRegularization = markPendingRegularization;
  }

  @PostMapping("/pending-regularization")
  Marked mark(HttpServletRequest request) {
    // a parameter nobody declared, or one given twice, is refused and not ignored
    for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
      if (!QUERY.contains(parameter.getKey()) || parameter.getValue().length != 1) {
        throw new InvalidDataException("parameter", "A parameter of the request is not declared by this route");
      }
    }
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    return new Marked(markPendingRegularization.execute(new MarkPendingRegularization.Command(caller, limit(request), MDC.get("traceId"))));
  }

  private static int limit(HttpServletRequest request) {
    String value = request.getParameter("limit");
    if (value == null) {
      return DEFAULT_LIMIT;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException notANumber) {
      throw new InvalidDataException("limit", "The limit is a whole number from 1 to " + MarkPendingRegularization.MAX_LIMIT);
    }
  }
}
