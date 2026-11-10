package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.CostMode;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ListCosts;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OperationalCost;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RecordCost;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operational costs of a departure (HU-OPS-007). Who may call is judged before the request is read ({@code RouteAuthorization}); the
 * tenant is the one of the token and nowhere else. The entity of the domain is never serialized: the response is built here.
 */
@RestController
@RequestMapping("/api/v1/operations-cost/departures/{departureCode}/costs")
class CostController {

  private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("SAL-[0-9]{3,6}");
  private static final String DEPARTURES = "/api/v1/operations-cost/departures/";

  /** The body of {@code recordCost}; a field that is not declared is refused by the JSON configuration. */
  record RecordCostRequest(String concept, String mode, Long amountCents, String justification) {}

  /** The {@code OperationalCost} of the contract: what a calculated cost used, or the justification, is present only when it exists. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CostResponse(
      UUID costId, String concept, long amountCents, String mode, Long unitCostCents, Integer travelersServed, boolean withoutParametrization,
      String justification, Instant recordedAt) {

    static CostResponse of(OperationalCost cost) {
      return new CostResponse(cost.costId(), cost.concept(), cost.amountCents(), cost.mode().name(), cost.unitCostCents(), cost.travelersServed(),
          cost.withoutParametrization(), cost.justification(), cost.recordedAt());
    }
  }

  /** The {@code CostSheet} of the contract; the catalogued cost is absent when the tour has none. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SheetResponse(List<CostResponse> data, long totalCents, int travelersServed, Long baseCostCents) {}

  private final RecordCost recordCost;
  private final ListCosts listCosts;

  CostController(RecordCost recordCost, ListCosts listCosts) {
    this.recordCost = recordCost;
    this.listCosts = listCosts;
  }

  @PostMapping(consumes = "application/json")
  ResponseEntity<CostResponse> record(
      HttpServletRequest request, @PathVariable String departureCode,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody RecordCostRequest body) {
    requireCode(departureCode);
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    RecordCost.Recorded recorded = recordCost.execute(new RecordCost.Command(
        caller, departureCode, idempotencyKey, body.concept(), modeOf(body.mode()), body.amountCents(), body.justification(), MDC.get("traceId")));
    CostResponse response = CostResponse.of(recorded.cost());
    return recorded.recorded()
        ? ResponseEntity.status(HttpStatus.CREATED).location(URI.create(DEPARTURES + departureCode + "/costs")).body(response)
        : ResponseEntity.ok(response);
  }

  @GetMapping
  SheetResponse list(HttpServletRequest request, @PathVariable String departureCode) {
    requireCode(departureCode);
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    ListCosts.Sheet sheet = listCosts.execute(new ListCosts.Query(caller, departureCode));
    return new SheetResponse(sheet.costs().stream().map(CostResponse::of).toList(), sheet.totalCents(), sheet.travelersServed(),
        sheet.baseCostCents().orElse(null));
  }

  private static CostMode modeOf(String value) {
    try {
      return value == null ? null : CostMode.valueOf(value);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidDataException("mode", "The mode is not one of CALCULATED or MANUAL");
    }
  }

  private static void requireCode(String departureCode) {
    if (departureCode == null || !CODE.matcher(departureCode).matches()) {
      throw new InvalidDataException("departureCode", "The field departureCode is missing or not valid");
    }
  }
}
