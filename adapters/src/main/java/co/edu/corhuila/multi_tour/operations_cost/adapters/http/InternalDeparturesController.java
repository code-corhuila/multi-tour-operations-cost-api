package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ListDueToStartDepartures;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.MarkPendingRegularization;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What job 3 asks of the departures (HU-OPS-003 part B). Only the SERVICE role opens these routes ({@code RouteAuthorization}) and the
 * gateway never routes them (ADR-004 D5). The mark answers a count and nothing of any tenant; the due departures answer only codes of the
 * tenant of the path, because a service token carries none (job 3 spec 002).
 */
@RestController
@RequestMapping("/api/v1/operations-cost/internal")
class InternalDeparturesController {

  private static final int DEFAULT_LIMIT = 100;
  private static final Set<String> QUERY = Set.of("limit");

  record Marked(int marked) {}

  record DueDeparture(String departureCode) {}

  record DueDepartures(List<DueDeparture> data) {}

  private final MarkPendingRegularization markPendingRegularization;
  private final ListDueToStartDepartures listDueToStartDepartures;

  InternalDeparturesController(MarkPendingRegularization markPendingRegularization, ListDueToStartDepartures listDueToStartDepartures) {
    this.markPendingRegularization = markPendingRegularization;
    this.listDueToStartDepartures = listDueToStartDepartures;
  }

  @PostMapping("/departures/pending-regularization")
  Marked mark(HttpServletRequest request) {
    onlyDeclared(request);
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    return new Marked(markPendingRegularization.execute(new MarkPendingRegularization.Command(
        caller, limit(request, DEFAULT_LIMIT, MarkPendingRegularization.MAX_LIMIT), MDC.get("traceId"))));
  }

  @GetMapping("/tenants/{tenantId}/departures/due-to-start")
  DueDepartures dueToStart(@PathVariable String tenantId, HttpServletRequest request) {
    onlyDeclared(request);
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    List<String> codes = listDueToStartDepartures.execute(new ListDueToStartDepartures.Command(caller, tenant(tenantId),
        limit(request, ListDueToStartDepartures.DEFAULT_LIMIT, ListDueToStartDepartures.MAX_LIMIT)));
    return new DueDepartures(codes.stream().map(DueDeparture::new).toList());
  }

  private static void onlyDeclared(HttpServletRequest request) {
    // a parameter nobody declared, or one given twice, is refused and not ignored
    for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
      if (!QUERY.contains(parameter.getKey()) || parameter.getValue().length != 1) {
        throw new InvalidDataException("parameter", "A parameter of the request is not declared by this route");
      }
    }
  }

  private static int limit(HttpServletRequest request, int byDefault, int max) {
    String value = request.getParameter("limit");
    if (value == null) {
      return byDefault;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException notANumber) {
      throw new InvalidDataException("limit", "The limit is a whole number from 1 to " + max);
    }
  }

  private static UUID tenant(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      throw new InvalidDataException("tenantId", "The tenant is not a valid identifier");
    }
  }
}
