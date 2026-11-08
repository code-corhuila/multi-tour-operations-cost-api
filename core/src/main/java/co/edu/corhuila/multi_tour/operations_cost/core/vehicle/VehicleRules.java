package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.BusinessRuleViolationException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The two rules about vehicles that scheduling, rescheduling and adding vehicles all apply, inside their transaction (RN-OPS-API-33). */
public final class VehicleRules {

  private VehicleRules() {}

  /** Locks the vehicles and refuses the request if any of them is not one of the tenant (unknown, or of another tenant). */
  public static List<Vehicle> requireKnown(Vehicles vehicles, UUID tenantId, Collection<UUID> vehicleIds) {
    List<Vehicle> found = vehicles.lockAll(tenantId, vehicleIds);
    if (found.size() != vehicleIds.size()) {
      throw new BusinessRuleViolationException("A vehicle is unknown");
    }
    return found;
  }

  /** Refuses the request if one of the vehicles is on another departure whose interval overlaps; the message names that departure. */
  public static void requireFree(Vehicles vehicles, UUID tenantId, Collection<UUID> vehicleIds, Instant start, Instant end, UUID except) {
    if (vehicleIds.isEmpty()) {
      return;
    }
    vehicles.overlapping(tenantId, vehicleIds, start, end, except).ifPresent(code -> {
      throw new BusinessRuleViolationException("A vehicle is already on the departure " + code + " at that time");
    });
  }
}
