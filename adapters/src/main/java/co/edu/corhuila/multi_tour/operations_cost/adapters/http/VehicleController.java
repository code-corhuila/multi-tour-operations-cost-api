package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.ListVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.RegisterVehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vehicles of the tenant (HU-OPS-002). The role is judged before the request is read ({@code RouteAuthorization}); the tenant is
 * the one of the token and nowhere else. The entity of the domain is never serialized: the response is built here.
 */
@RestController
@RequestMapping("/api/v1/operations-cost/vehicles")
class VehicleController {

  private static final String LIST = "/api/v1/operations-cost/vehicles";

  /** The body of {@code createVehicle}; a field that is not declared is refused by the JSON configuration. */
  record CreateVehicleRequest(String name, int capacity) {}

  record Meta(int page, int limit, long total, int totalPages) {}

  record VehiclePage(List<VehicleResponse> data, Meta meta) {}

  private final RegisterVehicle registerVehicle;
  private final ListVehicles listVehicles;

  VehicleController(RegisterVehicle registerVehicle, ListVehicles listVehicles) {
    this.registerVehicle = registerVehicle;
    this.listVehicles = listVehicles;
  }

  @PostMapping(consumes = "application/json")
  ResponseEntity<VehicleResponse> register(
      HttpServletRequest request,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CreateVehicleRequest body) {
    // the key is judged by the use case before the body: a repeat answers 200 whatever it carries (Annex C)
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    RegisterVehicle.Registered result = registerVehicle.execute(
        new RegisterVehicle.Command(caller, idempotencyKey, body.name(), body.capacity(), MDC.get("traceId")));
    VehicleResponse response = VehicleResponse.of(result.vehicle());
    return result.created()
        ? ResponseEntity.status(HttpStatus.CREATED).location(URI.create(LIST)).body(response)
        : ResponseEntity.ok(response);
  }

  @GetMapping
  VehiclePage list(
      HttpServletRequest request, @RequestParam(name = "page", defaultValue = "1") int page,
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    Caller caller = (Caller) request.getAttribute(JwtAuthFilter.CALLER);
    Page<Vehicle> found = listVehicles.execute(new ListVehicles.Query(caller, page, limit));
    return new VehiclePage(found.data().stream().map(VehicleResponse::of).toList(),
        new Meta(found.page(), found.limit(), found.total(), found.totalPages()));
  }
}
