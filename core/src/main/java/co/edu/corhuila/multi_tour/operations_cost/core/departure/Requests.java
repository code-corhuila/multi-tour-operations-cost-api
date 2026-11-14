package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import java.util.List;
import java.util.UUID;

/** What every request is checked for before anything else is asked or written: the same checks, once. */
public final class Requests {

  private static final int MIN_KEY = 8;
  private static final int MAX_KEY = 128;
  private static final int MAX_TRACE = 128;
  public static final int MAX_VEHICLES = 20;

  private Requests() {}

  public static void requireKey(String key) {
    if (key == null || key.length() < MIN_KEY || key.length() > MAX_KEY || key.chars().anyMatch(Character::isWhitespace)) {
      throw new InvalidDataException("Idempotency-Key", "The Idempotency-Key must have 8 to 128 characters and no white space");
    }
  }

  public static void requireTrace(String trace) {
    if (trace == null || trace.isBlank() || trace.length() > MAX_TRACE) {
      throw new InvalidDataException("X-Correlation-Id", "The correlation id must have 1 to 128 characters");
    }
  }

  /** The account or the tenant of a token must be a UUID, as the database stores them. */
  public static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      throw new CallerNotAllowedException();
    }
  }

  /** The ids of vehicles of a request: at least {@code min}, at most 20, none missing and none repeated. */
  public static List<UUID> vehicleIds(List<UUID> ids, int min) {
    if (ids == null || ids.size() < min || ids.size() > MAX_VEHICLES || ids.stream().anyMatch(java.util.Objects::isNull) || ids.stream().distinct().count() != ids.size()) {
      throw new InvalidDataException("vehicleIds", "The field vehicleIds must have " + min + " to " + MAX_VEHICLES + " different ids");
    }
    return List.copyOf(ids);
  }
}
