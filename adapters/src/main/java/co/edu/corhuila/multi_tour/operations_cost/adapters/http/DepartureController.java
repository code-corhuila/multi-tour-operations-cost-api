package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.AddDepartureVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RescheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ScheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleCapacity;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The departures of the tenant. The role is judged before the request is read ({@code RouteAuthorization}); the tenant is the
 * one of the token and nowhere else (RN-OPS-API-02). The entity of the domain is never serialized: the response is built here.
 */
@RestController
@RequestMapping("/api/v1/operations-cost")
class DepartureController {

  /** The body of {@code scheduleDeparture}; a field that is not declared is refused by the JSON configuration. */
  record ScheduleDepartureRequest(UUID serviceId, Instant scheduledStart, Instant scheduledEnd, String timeZone, List<UUID> vehicleIds) {}

  /**
   * The {@code Departure} of the contract. {@code capacity} is the effective one: the capacity of the tour, or the lower of it and
   * the total of the vehicles when the tour requires transport (HU-OPS-002); what this story does not yet fill (reservations,
   * novelties) is empty.
   */
  record DepartureResponse(
      String code, String status, Instant scheduledStart, Instant scheduledEnd, int capacity, int booked, int tourCapacity,
      int vehicleCapacity, boolean transportRequired, List<VehicleResponse> vehicles, List<Object> reservations,
      List<Object> novelties) {

    static DepartureResponse of(Departure departure, int tourCapacity, boolean transportRequired, List<Vehicle> vehicles) {
      int vehicleTotal = VehicleCapacity.total(vehicles);
      return new DepartureResponse(departure.code(), departure.status().name(), departure.start(), departure.estimatedEnd(),
          VehicleCapacity.effective(tourCapacity, transportRequired, vehicleTotal), 0, tourCapacity, vehicleTotal, transportRequired,
          vehicles.stream().map(VehicleResponse::of).toList(), List.of(), List.of());
    }
  }

  /** The body of {@code rescheduleDeparture}: the new start and estimated end, nothing else. */
  record RescheduleDepartureRequest(Instant scheduledStart, Instant scheduledEnd) {}

  /** The body of {@code addDepartureVehicles}. */
  record AddDepartureVehiclesRequest(List<UUID> vehicleIds) {}

  private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("SAL-[0-9]{3,6}");

  private final ScheduleDeparture scheduleDeparture;
  private final RescheduleDeparture rescheduleDeparture;
  private final AddDepartureVehicles addDepartureVehicles;

  DepartureController(
      ScheduleDeparture scheduleDeparture, RescheduleDeparture rescheduleDeparture, AddDepartureVehicles addDepartureVehicles) {
    this.scheduleDeparture = scheduleDeparture;
    this.rescheduleDeparture = rescheduleDeparture;
    this.addDepartureVehicles = addDepartureVehicles;
  }

  @PutMapping(path = "/departures/{departureCode}/schedule", consumes = "application/json")
  ResponseEntity<DepartureResponse> reschedule(
      HttpServletRequest request, @PathVariable String departureCode, @RequestBody RescheduleDepartureRequest body) {
    requireCode(departureCode);
    require(body.scheduledStart() != null, "scheduledStart");
    require(body.scheduledEnd() != null, "scheduledEnd");
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    RescheduleDeparture.Rescheduled result = rescheduleDeparture.execute(new RescheduleDeparture.Command(
        caller, departureCode, body.scheduledStart(), body.scheduledEnd(), MDC.get("traceId")));
    return ResponseEntity.ok(DepartureResponse.of(result.departure(), result.capacity(), result.transportRequired(), result.vehicles()));
  }

  @PostMapping(path = "/departures", consumes = "application/json")
  ResponseEntity<DepartureResponse> schedule(
      HttpServletRequest request,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ScheduleDepartureRequest body) {
    // the shape of the body is judged by the use case after the key: a repeat answers 200 whatever it carries (Annex C)
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    ScheduleDeparture.Scheduled result = scheduleDeparture.execute(new ScheduleDeparture.Command(
        caller, idempotencyKey, body.serviceId(), body.scheduledStart(), body.scheduledEnd(), body.timeZone(),
        MDC.get("traceId"), body.vehicleIds() == null ? List.of() : body.vehicleIds()));
    Departure departure = result.departure();
    DepartureResponse response = DepartureResponse.of(departure, result.capacity(), result.transportRequired(), result.vehicles());
    if (!result.created()) {
      return ResponseEntity.ok(response);
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/operations-cost/departures/" + departure.code()))
        .body(response);
  }

  @PostMapping(path = "/departures/{departureCode}/vehicles", consumes = "application/json")
  ResponseEntity<DepartureResponse> addVehicles(
      HttpServletRequest request, @PathVariable String departureCode, @RequestBody AddDepartureVehiclesRequest body) {
    requireCode(departureCode);
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    AddDepartureVehicles.Added result = addDepartureVehicles.execute(
        new AddDepartureVehicles.Command(caller, departureCode, body.vehicleIds(), MDC.get("traceId")));
    return ResponseEntity.ok(DepartureResponse.of(
        result.departure(), result.tour().capacity(), result.tour().transportRequired(), result.vehicles()));
  }

  private static void requireCode(String departureCode) {
    require(departureCode != null && CODE.matcher(departureCode).matches(), "departureCode");
  }

  private static void require(boolean condition, String field) {
    if (!condition) {
      throw new InvalidDataException(field, "The field " + field + " is missing or not valid");
    }
  }
}
