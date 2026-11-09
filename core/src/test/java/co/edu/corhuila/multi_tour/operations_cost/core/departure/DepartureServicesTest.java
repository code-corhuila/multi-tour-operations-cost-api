package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Services are recorded only while the departure runs (RN-OPS-API-60). */
class DepartureServicesTest {

  private static final Instant START = Instant.parse("2026-11-10T08:00:00Z");

  private static Departure inState(DepartureStatus status) {
    return new Departure(UUID.randomUUID(), UUID.randomUUID(), "SAL-001", UUID.randomUUID(), START, START.plus(Duration.ofHours(8)),
        "America/Bogota", status, UUID.randomUUID(), START.minus(Duration.ofDays(3)));
  }

  @Test
  void D_services_only_while_in_execution() {
    assertThatCode(() -> inState(DepartureStatus.IN_EXECUTION).acceptServices()).doesNotThrowAnyException();
    for (DepartureStatus other : new DepartureStatus[] {DepartureStatus.SCHEDULED, DepartureStatus.FINISHED, DepartureStatus.CANCELLED}) {
      assertThatThrownBy(() -> inState(other).acceptServices()).isInstanceOf(InvalidStatusTransitionException.class);
    }
  }
}
