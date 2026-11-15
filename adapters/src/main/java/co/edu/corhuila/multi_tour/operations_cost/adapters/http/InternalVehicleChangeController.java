package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.PreviewVehicleChange;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ReplaceDepartureVehicles;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the saga that changes the vehicles of a departure asks (HU-OPS-002 part B, api spec 008). Only the SERVICE role opens these routes
 * ({@code RouteAuthorization}) and the gateway never routes them (ADR-004 D5): the tenant is the one of the path, because a service token
 * carries none. The entity of the domain is never serialized: the response is built here.
 */
@RestController
@RequestMapping("/api/v1/operations-cost/internal/tenants/{tenantId}/departures/{departureCode}")
class InternalVehicleChangeController {

  private static final Pattern CODE = Pattern.compile("SAL-[0-9]{3,6}");

  /** The body of both routes: the whole new set of vehicles. */
  record VehicleSetRequest(List<UUID> vehicleIds) {}

  /** The answer of the preview. */
  record VehicleChangePreview(UUID departureId, int currentEffectiveCapacity, int newEffectiveCapacity) {}

  private final PreviewVehicleChange previewVehicleChange;
  private final ReplaceDepartureVehicles replaceDepartureVehicles;
  private final Clock clock;

  InternalVehicleChangeController(PreviewVehicleChange previewVehicleChange, ReplaceDepartureVehicles replaceDepartureVehicles, Clock clock) {
    this.previewVehicleChange = previewVehicleChange;
    this.replaceDepartureVehicles = replaceDepartureVehicles;
    this.clock = clock;
  }

  @PostMapping(path = "/vehicle-changes/preview", consumes = "application/json")
  VehicleChangePreview preview(@PathVariable String tenantId, @PathVariable String departureCode, @RequestBody VehicleSetRequest body) {
    PreviewVehicleChange.Preview answer = previewVehicleChange.execute(
        new PreviewVehicleChange.Command(tenant(tenantId), code(departureCode), body.vehicleIds()));
    return new VehicleChangePreview(answer.departureId(), answer.currentEffectiveCapacity(), answer.newEffectiveCapacity());
  }

  @PutMapping(path = "/vehicles", consumes = "application/json")
  DepartureController.DepartureResponse replace(
      @PathVariable String tenantId, @PathVariable String departureCode,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody VehicleSetRequest body) {
    ReplaceDepartureVehicles.Replaced result = replaceDepartureVehicles.execute(new ReplaceDepartureVehicles.Command(
        tenant(tenantId), code(departureCode), body.vehicleIds(), idempotencyKey, MDC.get("traceId")));
    return DepartureController.DepartureResponse.of(
        result.departure(), result.tour().capacity(), result.tour().transportRequired(), result.vehicles(), clock.instant());
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
