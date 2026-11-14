package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The effective capacity is computed in the core and nowhere else (api 004 plan D-06, RN-OPS-API-32). */
class DepartureViewTest {

  private static final Instant START = Instant.parse("2026-11-10T08:00:00Z");
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);

  private static Departure departure() {
    return new Departure(new UUID(0, 1), TENANT, "SAL-001", new UUID(0, 0xbb), START, START.plus(Duration.ofHours(8)), "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, START.minus(Duration.ofDays(2)));
  }

  private static Vehicle vehicle(int n, int capacity) {
    return new Vehicle(new UUID(0, 2000 + n), TENANT, "Vehicle " + n, capacity, ACCOUNT, START);
  }

  @Test
  void D_a_tour_that_requires_transport_is_the_lower_of_its_capacity_and_its_vehicles() {
    DepartureView view = DepartureView.of(departure(), 18, true, 5, List.of(vehicle(1, 12), vehicle(2, 30)));
    assertThat(view.vehicleCapacity()).isEqualTo(42);
    assertThat(view.capacity()).isEqualTo(18);
    assertThat(view.tourCapacity()).isEqualTo(18);
    assertThat(view.booked()).isEqualTo(5);
    assertThat(DepartureView.of(departure(), 18, true, 0, List.of(vehicle(1, 12))).capacity()).isEqualTo(12);
    assertThat(DepartureView.of(departure(), 18, true, 0, List.of()).capacity()).isZero();
  }

  @Test
  void D_a_tour_without_transport_keeps_its_own_capacity_whatever_the_vehicles() {
    assertThat(DepartureView.of(departure(), 18, false, 0, List.of()).capacity()).isEqualTo(18);
    assertThat(DepartureView.of(departure(), 18, false, 0, List.of(vehicle(1, 5))).capacity()).isEqualTo(18);
  }
}
