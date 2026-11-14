package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.AddDepartureVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureExecution;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureView;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.FinishDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.GetDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ExtraordinaryCancellation;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RecordExtraordinaryCancellation;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.SaveServices;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServiceRecord;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServiceResult;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServicesView;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ListDepartures;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RegularizeDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RescheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ScheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.StartDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
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
   * interruption) is present only once it exists. The reservations and the real base are answered by the reading and by saving the
   * services, and only when their owner could be read; the novelties come with HU-OPS-008.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record DepartureResponse(
      String code, UUID serviceId, String status, Instant scheduledStart, Instant scheduledEnd, int capacity, int booked, int tourCapacity,
      int vehicleCapacity, boolean transportRequired, boolean pendingRegularization, boolean startAlert, Instant actualStart, Instant actualEnd,
      boolean interrupted, String interruptionCause, List<VehicleResponse> vehicles, Integer travelersServed,
      List<ReservationResponse> reservations) {

    /** The answers of scheduling, rescheduling and adding vehicles: they do not read the seats held, so they say 0. */
    static DepartureResponse of(Departure departure, int tourCapacity, boolean transportRequired, List<Vehicle> vehicles, Instant now) {
      return of(DepartureView.of(departure, tourCapacity, transportRequired, 0, vehicles), now);
    }

    /** The capacity is the one the core computed: the route carries it and computes nothing. */
    static DepartureResponse of(DepartureView view, Instant now) {
      Departure departure = view.departure();
      DepartureExecution run = departure.execution();
      return new DepartureResponse(departure.code(), departure.serviceId(), departure.status().name(), departure.start(), departure.estimatedEnd(),
          view.capacity(), view.booked(), view.tourCapacity(), view.vehicleCapacity(), view.transportRequired(),
          run.pendingRegularization(), departure.startAlert(now), run.actualStart(), run.actualEnd(), run.interrupted(), run.interruptionCause(),
          view.vehicles().stream().map(VehicleResponse::of).toList(), null, null);
    }

    /** The same, with the reservations of the departure and what each received. */
    static DepartureResponse of(DepartureView view, java.util.Optional<ServicesView> services, Instant now) {
      DepartureResponse plain = of(view, now);
      if (services.isEmpty()) {
        return plain;
      }
      return new DepartureResponse(plain.code(), plain.serviceId(), plain.status(), plain.scheduledStart(), plain.scheduledEnd(), plain.capacity(),
          plain.booked(), plain.tourCapacity(), plain.vehicleCapacity(), plain.transportRequired(), plain.pendingRegularization(), plain.startAlert(),
          plain.actualStart(), plain.actualEnd(), plain.interrupted(), plain.interruptionCause(), plain.vehicles(), services.get().travelersServed(),
          services.get().reservations().stream().map(ReservationResponse::of).toList());
    }
  }

  /** A reservation of the departure as its owner says it, with what was recorded for it once it exists. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ReservationResponse(
      UUID reservationId, String customer, int travelers, String state, String result, Integer travelersServed, Boolean noShow, String cause,
      CancellationResponse extraordinaryCancellation) {

    static ReservationResponse of(ServicesView.ReservationView view) {
      var reservation = view.reservation();
      var recorded = view.recorded();
      return new ReservationResponse(reservation.reservationId(), reservation.customer(), reservation.travelers(), reservation.state().name(),
          recorded.map(r -> r.result().name()).orElse(null), recorded.map(ServiceRecord::travelersServed).orElse(null),
          recorded.map(ServiceRecord::noShow).orElse(null), recorded.map(ServiceRecord::cause).orElse(null),
          view.cancellation().map(CancellationResponse::of).orElse(null));
    }
  }

  /** The extraordinary cancellation of a reservation: why, by whom and when (HU-OPS-005). */
  record CancellationResponse(String justification, UUID recordedBy, Instant recordedAt) {

    static CancellationResponse of(ExtraordinaryCancellation cancellation) {
      return new CancellationResponse(cancellation.justification(), cancellation.recordedBy(), cancellation.recordedAt());
    }
  }

  record DeparturePage(List<DepartureResponse> data, Meta meta) {}

  record Meta(int page, int limit, long total, int totalPages) {}

  /** The body of {@code registerStart}. */
  record StartExecutionRequest(Instant startedAt) {}

  /** The body of {@code finishDeparture}: a normal end carries no cause, an interruption carries it. */
  record FinishRequest(Instant finishedAt, String result, String cause) {}

  /**
   * The body of {@code regularizeDeparture}: the result, the real hours and what the result needs; {@code notCarriedOutCause} is the
   * written reason of {@code NOT_CARRIED_OUT}.
   */
  record RegularizeRequest(
      String result, Instant actualStart, Instant actualEnd, String earlyStartReason, String interruption, String notCarriedOutCause) {}

  /** The body of {@code rescheduleDeparture}: the new start and estimated end, nothing else. */
  record RescheduleDepartureRequest(Instant scheduledStart, Instant scheduledEnd) {}

  /** The body of {@code saveServices}: one entry per reservation; what the entry means is judged by the core. */
  record SaveServicesRequest(List<ServiceEntryRequest> services) {}

  /** The body of {@code recordExtraordinaryCancellation}; whether the text is a justification is judged by the core. */
  record ExtraordinaryCancellationRequest(String justification) {}

  record ServiceEntryRequest(UUID reservationId, String result, Integer travelersServed, Boolean noShow, String cause) {}

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
  private final RegularizeDeparture regularizeDeparture;
  private final SaveServices saveServices;
  private final RecordExtraordinaryCancellation recordCancellation;
  private final Clock clock;

  DepartureController(
      ScheduleDeparture scheduleDeparture, RescheduleDeparture rescheduleDeparture, AddDepartureVehicles addDepartureVehicles,
      ListDepartures listDepartures, GetDeparture getDeparture, StartDeparture startDeparture, FinishDeparture finishDeparture,
      RegularizeDeparture regularizeDeparture, SaveServices saveServices, RecordExtraordinaryCancellation recordCancellation, Clock clock) {
    this.scheduleDeparture = scheduleDeparture;
    this.rescheduleDeparture = rescheduleDeparture;
    this.addDepartureVehicles = addDepartureVehicles;
    this.listDepartures = listDepartures;
    this.getDeparture = getDeparture;
    this.startDeparture = startDeparture;
    this.finishDeparture = finishDeparture;
    this.regularizeDeparture = regularizeDeparture;
    this.saveServices = saveServices;
    this.recordCancellation = recordCancellation;
    this.clock = clock;
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
    // one instant for the whole page, so every row is judged at the same moment
    Instant now = clock.instant();
    return new DeparturePage(found.data().stream().map(view -> DepartureResponse.of(view, now)).toList(),
        new Meta(found.page(), found.limit(), found.total(), found.totalPages()));
  }

  @GetMapping("/departures/{departureCode}")
  DepartureResponse read(HttpServletRequest request, @PathVariable String departureCode) {
    requireCode(departureCode);
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    GetDeparture.Found found = getDeparture.execute(new GetDeparture.Query(caller, departureCode));
    return DepartureResponse.of(found.view(), found.services(), clock.instant());
  }

  @PostMapping(path = "/departures/{departureCode}/services", consumes = "application/json")
  ResponseEntity<DepartureResponse> saveServices(
      HttpServletRequest request, @PathVariable String departureCode,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody SaveServicesRequest body) {
    requireCode(departureCode);
    require(body.services() != null, "services");
    List<ServiceRecord> entries = body.services().stream().map(DepartureController::recordOf).toList();
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    SaveServices.Saved saved = saveServices.execute(new SaveServices.Command(caller, departureCode, idempotencyKey, entries, MDC.get("traceId")));
    DepartureResponse response = DepartureResponse.of(saved.view(), saved.services(), clock.instant());
    return saved.recorded()
        ? ResponseEntity.status(HttpStatus.CREATED).location(URI.create(DEPARTURES + "/" + saved.view().departure().code())).body(response)
        : ResponseEntity.ok(response);
  }

  @PostMapping(path = "/departures/{departureCode}/reservations/{reservationId}/extraordinary-cancellation", consumes = "application/json")
  ResponseEntity<DepartureResponse> recordExtraordinaryCancellation(
      HttpServletRequest request, @PathVariable String departureCode, @PathVariable String reservationId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody ExtraordinaryCancellationRequest body) {
    requireCode(departureCode);
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    RecordExtraordinaryCancellation.Recorded recorded = recordCancellation.execute(
        new RecordExtraordinaryCancellation.Command(caller, departureCode, reservationId, idempotencyKey, body.justification(), MDC.get("traceId")));
    DepartureResponse response = DepartureResponse.of(recorded.view(), recorded.services(), clock.instant());
    return recorded.recorded()
        ? ResponseEntity.status(HttpStatus.CREATED).location(URI.create(DEPARTURES + "/" + recorded.view().departure().code())).body(response)
        : ResponseEntity.ok(response);
  }

  /** The shape of one entry is judged here; what its numbers mean is judged by {@link ServiceRecord}. */
  private static ServiceRecord recordOf(ServiceEntryRequest entry) {
    require(entry != null && entry.reservationId() != null && entry.travelersServed() != null, "services");
    ServiceResult result;
    try {
      result = ServiceResult.valueOf(String.valueOf(entry.result()));
    } catch (IllegalArgumentException unknown) {
      throw new InvalidDataException("result", "The result is not one of DELIVERED or NOT_DELIVERED");
    }
    return new ServiceRecord(entry.reservationId(), result, entry.travelersServed(), entry.cause(), Boolean.TRUE.equals(entry.noShow()));
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

  @PostMapping(path = "/departures/{departureCode}/regularization", consumes = "application/json")
  ResponseEntity<DepartureResponse> regularize(
      HttpServletRequest request, @PathVariable String departureCode,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody RegularizeRequest body) {
    requireCode(departureCode);
    // the shape of the body that this route can judge alone; the rest is judged by the use case, after the key
    RegularizeDeparture.Result result = resultOf(body.result());
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    RegularizeDeparture.Regularized done = regularizeDeparture.execute(new RegularizeDeparture.Command(
        caller, departureCode, idempotencyKey, result, body.actualStart(), body.actualEnd(), body.earlyStartReason(), body.interruption(),
        body.notCarriedOutCause(), MDC.get("traceId")));
    return recorded(done.view(), done.recorded());
  }

  private static RegularizeDeparture.Result resultOf(String value) {
    try {
      return value == null ? null : RegularizeDeparture.Result.valueOf(value);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidDataException("result", "The result is not one of EXECUTED, INTERRUPTED or NOT_CARRIED_OUT");
    }
  }

  private ResponseEntity<DepartureResponse> recorded(DepartureView view, boolean created) {
    DepartureResponse response = DepartureResponse.of(view, clock.instant());
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
    return ResponseEntity.ok(DepartureResponse.of(result.departure(), result.capacity(), result.transportRequired(), result.vehicles(), clock.instant()));
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
    DepartureResponse response = DepartureResponse.of(departure, result.capacity(), result.transportRequired(), result.vehicles(), clock.instant());
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
        result.departure(), result.tour().capacity(), result.tour().transportRequired(), result.vehicles(), clock.instant()));
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
