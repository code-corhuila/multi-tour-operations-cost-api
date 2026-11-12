package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CancelDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Cancellation;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the saga {@code cancel-departure} asks first (HU-OPS-006, api spec 010). Only the SERVICE role opens it ({@code RouteAuthorization})
 * and the gateway never routes it (ADR-004 D5): the tenant is the one of the path, because a service token carries none. Who asked and
 * with which role are optional fields the saga may send; the role is one of the two that may cancel, and any other is a bad request.
 */
@RestController
@RequestMapping("/api/v1/operations-cost/internal/tenants/{tenantId}/departures/{departureCode}")
class InternalCancellationController {

  private static final Pattern CODE = Pattern.compile("SAL-[0-9]{3,6}");

  /** The body: the cause, and who asked when a person did. */
  record CancelRequest(String cause, UUID requestedBy, String requestedByRole) {}

  /** The answer: the departure and the instant it was cancelled, the original one for a repeat. */
  record CancelAnswer(UUID departureId, Instant cancelledAt) {}

  private final CancelDeparture cancelDeparture;

  InternalCancellationController(CancelDeparture cancelDeparture) {
    this.cancelDeparture = cancelDeparture;
  }

  @PostMapping(path = "/cancellation", consumes = "application/json")
  CancelAnswer cancel(
      @PathVariable String tenantId, @PathVariable String departureCode,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody CancelRequest body) {
    CancelDeparture.Cancelled done = cancelDeparture.execute(new CancelDeparture.Command(
        tenant(tenantId), code(departureCode), idempotencyKey, cause(body.cause()), body.requestedBy(), role(body.requestedByRole()),
        MDC.get("traceId")));
    return new CancelAnswer(done.departureId(), done.cancelledAt());
  }

  private static Cancellation.Cause cause(String value) {
    if (!Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER.name().equals(value)) {
      throw new InvalidDataException("cause", "The cause of this cancellation is NOT_ATTRIBUTABLE_TO_CUSTOMER");
    }
    return Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER;
  }

  private static Role role(String value) {
    if (value == null) {
      return null;
    }
    if (!Role.TENANT_ADMINISTRATOR.name().equals(value) && !Role.OPERATIONAL_COLLABORATOR.name().equals(value)) {
      throw new InvalidDataException("requestedByRole", "The role is TENANT_ADMINISTRATOR or OPERATIONAL_COLLABORATOR");
    }
    return Role.valueOf(value);
  }

  private static UUID tenant(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      throw new InvalidDataException("tenantId", "The tenant is not a valid identifier");
    }
  }

  private static String code(String value) {
    if (value == null || !CODE.matcher(value).matches()) {
      throw new InvalidDataException("departureCode", "The departure code is not valid");
    }
    return value;
  }
}
