package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.AddDepartureVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureExecution;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureView;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.FinishDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.GetDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ListDepartures;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RescheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ScheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.StartDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
   * the total of the vehicles when the tour requires transport (HU-OPS-002). What a person recorded (the real times, the
   * interruption) is present only once it exists. Its reservations and novelties come with HU-OPS-004 and are not answered.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record DepartureResponse(
      String code, UUID serviceId, String status, Instant scheduledStart, Instant scheduledEnd, int capacity, int booked, int tourCapacity,
      int vehicleCapacity, boolean transportRequired, boolean pendingRegularization, Instant actualStart, Instant actualEnd,
      boolean interrupted, String interruptionCause, List<VehicleResponse> vehicles) {

    /** The answers of scheduling, rescheduling and adding vehicles: they do not read the seats held, so they say 0. */
    static DepartureResponse of(Departure departure, int tourCapacity, boolean transportRequired, List<Vehicle> vehicles) {
      return of(DepartureView.of(departure, tourCapacity, transportRequired, 0, vehicles));
    }

    /** The capacity is the one the core computed: the route carries it and computes nothing. */
    static DepartureResponse of(DepartureView view) {
      Departure departure = view.departure();
      DepartureExecution run = departure.execution();
      return new DepartureResponse(departure.code(), departure.serviceId(), departure.status().name(), departure.start(), departure.estimatedEnd(),
          view.capacity(), view.booked(), view.tourCapacity(), view.vehicleCapacity(), view.transportRequired(),
          false, run.actualStart(), run.actualEnd(), run.interrupted(), run.interruptionCause(),
          view.vehicles().stream().map(VehicleResponse::of).toList());
    }
  }

  record DeparturePage(List<DepartureResponse> data, Meta meta) {}

  record Meta(int page, int limit, long total, int totalPages) {}

  /** The body of {@code registerStart}. */
  record StartExecutionRequest(Instant startedAt) {}

  /** The body of {@code finishDeparture}: a normal end carries no cause, an interruption carries it. */
  record FinishRequest(Instant finishedAt, String result, String cause) {}

  /** The body of {@code rescheduleDeparture}: the new start and estimated end, nothing else. */
  record RescheduleDepartureRequest(Instant scheduledStart, Instant scheduledEnd) {}

  /** The body of {@code addDepartureVehicles}. */
  record AddDepartureVehiclesRequest(List<UUID> vehicleIds) {}

  private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("SAL-[0-9]{3,6}");
  private static final String DEPARTURES = "/api/v1/operations-cost/departures";
  private static final java.util.Set<String> LIST_PARAMETERS = java.util.Set.of("page", "limit", "status");

  private final ScheduleDeparture scheduleDeparture;
  private final RescheduleDeparture rescheduleDeparture;
  private final AddDepartureVehicles addDepartureVehicles;
  private final ListDepartures listDepartures;
  private final GetDeparture getDeparture;
  private final StartDeparture startDeparture;
  private final FinishDeparture finishDeparture;

  DepartureController(
      ScheduleDeparture scheduleDeparture, RescheduleDeparture rescheduleDeparture, AddDepartureVehicles addDepartureVehicles,
      ListDepartures listDepartures, GetDeparture getDeparture, StartDeparture startDeparture, FinishDeparture finishDeparture) {
    this.scheduleDeparture = scheduleDeparture;
    this.rescheduleDeparture = rescheduleDeparture;
    this.addDepartureVehicles = addDepartureVehicles;
    this.listDepartures = listDepartures;
    this.getDeparture = getDeparture;
    this.startDeparture = startDeparture;
    this.finishDeparture = finishDeparture;
  }

  /** A parameter the route does not declare is refused, never ignored (Annex C). */
  @GetMapping("/departures")
  DeparturePage list(
      HttpServletRequest request, @RequestParam(name = "page", defaultValue = "1") int page,
      @RequestParam(name = "limit", defaultValue = "20") int limit, @RequestParam(name = "status", required = false) String status) {
    // the name is the caller's text, so it is not echoed back
    if (!LIST_PARAMETERS.containsAll(request.getParameterMap().keySet())) {
      throw new InvalidDataException("parameter", "A parameter of the request is not declared by this route");
    }
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    Page<DepartureView> found = listDepartures.execute(new ListDepartures.Query(caller, Optional.ofNullable(status).map(DepartureController::statusOf), page, limit));
    return new DeparturePage(found.data().stream().map(DepartureResponse::of).toList(),
        new Meta(found.page(), found.limit(), found.total(), found.totalPages()));
  }

  @GetMapping("/departures/{departureCode}")
  DepartureResponse read(HttpServletRequest request, @PathVariable String departureCode) {
    requireCode(departureCode);
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    return DepartureResponse.of(getDeparture.execute(new GetDeparture.Query(caller, departureCode)));
  }

  @PostMapping(path = "/departures/{departureCode}/executions", consumes = "application/json")
  ResponseEntity<DepartureResponse> registerStart(
      HttpServletRequest request, @PathVariable String departureCode,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody StartExecutionRequest body) {
    requireCode(departureCode);
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    StartDeparture.Started result = startDeparture.execute(
        new StartDeparture.Command(caller, departureCode, idempotencyKey, body.startedAt(), MDC.get("traceId")));
    return recorded(result.view(), result.recorded());
  }

  @PostMapping(path = "/departures/{departureCode}/finish", consumes = "application/json")
  ResponseEntity<DepartureResponse> finish(
      HttpServletRequest request, @PathVariable String departureCode,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody FinishRequest body) {
    requireCode(departureCode);
    // the shape of the request is judged here: an interruption needs its cause and a normal end takes none
    boolean interrupted = "INTERRUPTED".equals(body.result());
    require(interrupted || "FINISHED".equals(body.result()), "result");
    require(interrupted == (body.cause() != null), "cause");
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    FinishDeparture.Finished result = finishDeparture.execute(
        new FinishDeparture.Command(caller, departureCode, idempotencyKey, body.finishedAt(), body.cause(), MDC.get("traceId")));
    return recorded(result.view(), result.recorded());
  }

  private static ResponseEntity<DepartureResponse> recorded(DepartureView view, boolean created) {
    DepartureResponse response = DepartureResponse.of(view);
    return created
        ? ResponseEntity.status(HttpStatus.CREATED).location(URI.create(DEPARTURES + "/" + view.departure().code())).body(response)
        : ResponseEntity.ok(response);
  }

  private static DepartureStatus statusOf(String value) {
    try {
      return DepartureStatus.valueOf(value);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidDataException("status", "The state is not one of the states of a departure");
    }
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
