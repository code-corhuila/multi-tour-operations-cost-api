package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A vehicle of a tenant: the name the operator gives it and the seats it has (RN-OPS-API-30). Its identity is its id; the name is
 * kept without the spaces around it and is unique in the tenant, which the repository and the database enforce. Every way of
 * building one, the reading of a stored one included, checks what the engine also checks.
 */
public record Vehicle(UUID id, UUID tenantId, String name, int capacity, UUID createdBy, Instant createdAt) {

  public static final int MAX_NAME = 60;
  public static final int MAX_CAPACITY = 100;

  public Vehicle {
    require(id != null, "id");
    require(tenantId != null, "tenantId");
    name = name == null ? null : name.strip();
    require(name != null && !name.isEmpty() && name.length() <= MAX_NAME, "name");
    require(capacity >= 1 && capacity <= MAX_CAPACITY, "capacity");
    require(createdBy != null, "createdBy");
    require(createdAt != null, "createdAt");
    // the database keeps microseconds: what is answered the first time is what is read back later
    createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
  }

  private static void require(boolean condition, String field) {
    if (!condition) {
      throw new InvalidDataException(field, "The field " + field + " is missing or not valid");
    }
  }
}
