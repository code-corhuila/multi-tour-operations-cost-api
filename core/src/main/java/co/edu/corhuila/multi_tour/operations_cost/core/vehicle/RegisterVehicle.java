package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.BusinessRuleViolationException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdGenerator;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Requests;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Transaction;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Registers a vehicle of the tenant (HU-OPS-002, AC1). The shape is judged before the repository is asked anything; the vehicle
 * and its idempotency key are written together. A repeat with the same key answers the vehicle already created, whatever its
 * body says (Annex C). The route judges the role; this judges that the caller is a person of a tenant.
 */
public final class RegisterVehicle {

  private final Vehicles vehicles;
  private final VehicleKeys keys;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  public RegisterVehicle(Vehicles vehicles, VehicleKeys keys, Transaction transaction, IdGenerator ids, Clock clock) {
    this.vehicles = vehicles;
    this.keys = keys;
    this.transaction = transaction;
    this.ids = ids;
    this.clock = clock;
  }

  public record Command(Caller caller, String idempotencyKey, String name, int capacity, String correlationId) {}

  /** The vehicle and whether this request created it (false: the key had been used). */
  public record Registered(Vehicle vehicle, boolean created) {}

  public Registered execute(Command command) {
    UUID tenantId = Requests.uuid(command.caller().tenantId());
    UUID account = Requests.uuid(command.caller().subject());
    String key = command.idempotencyKey();
    Requests.requireKey(key);
    Requests.requireTrace(command.correlationId());
    Optional<Vehicle> previous = alreadyCreated(tenantId, key);
    if (previous.isPresent()) {
      return new Registered(previous.get(), false);
    }
    Vehicle vehicle = new Vehicle(ids.next(), tenantId, command.name(), command.capacity(), account, clock.instant());
    if (vehicles.nameTaken(tenantId, vehicle.name())) {
      throw new BusinessRuleViolationException("A vehicle with that name already exists");
    }
    try {
      transaction.run(() -> {
        vehicles.save(vehicle);
        keys.registerVehicle(tenantId, key, vehicle.id());
        return null;
      });
      return new Registered(vehicle, true);
    } catch (DuplicateKeyException lostTheRace) {
      // another request with the same key committed first and this one rolled back: the answer is that one's vehicle
      return new Registered(alreadyCreated(tenantId, key).orElseThrow(() -> lostTheRace), false);
    }
  }

  private Optional<Vehicle> alreadyCreated(UUID tenantId, String key) {
    return keys.vehicleOf(tenantId, key).map(id -> vehicles.lockAll(tenantId, java.util.List.of(id)).stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("An idempotency key points to a vehicle that is not there")));
  }
}
