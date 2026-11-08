package co.edu.corhuila.multi_tour.operations_cost.core.outbox;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import java.time.Duration;

/** How long a failed delivery waits (it doubles up to a cap) and how many attempts it gets (ADR-004 D10). */
public record RetryPolicy(Duration base, Duration cap, int maxAttempts) {

  public RetryPolicy {
    if (base == null || cap == null || base.isNegative() || base.isZero() || cap.compareTo(base) < 0 || maxAttempts < 1
        || maxAttempts > 1000) {
      throw new InvalidDataException(null, "The retry needs a wait above zero, a cap not below it and one attempt at least");
    }
  }

  /** The wait after the n-th failed attempt: base, 2 x base, 4 x base... never above the cap. */
  public Duration waitAfter(int attempts) {
    Duration wait = base;
    for (int doubled = 1; doubled < attempts && wait.compareTo(cap) < 0; doubled++) {
      wait = wait.multipliedBy(2);
    }
    return wait.compareTo(cap) > 0 ? cap : wait;
  }
}
