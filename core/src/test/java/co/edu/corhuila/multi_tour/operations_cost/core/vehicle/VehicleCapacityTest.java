package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-OPS-002 (api spec 003), RN-OPS-API-32: the effective capacity is the lower of the tour's and the vehicles' total. */
class VehicleCapacityTest {

  private static Vehicle vehicle(int n, int capacity) {
    return new Vehicle(new UUID(0, n), new UUID(0, 99), "Vehicle " + n, capacity, new UUID(0, 98), Instant.parse("2026-11-09T10:00:00Z"));
  }

  @Test
  void D_effective_capacity_is_the_lower_value() {
    assertThat(VehicleCapacity.effective(18, true, 12)).isEqualTo(12);
    assertThat(VehicleCapacity.effective(18, true, 30)).isEqualTo(18);
    assertThat(VehicleCapacity.effective(18, true, 18)).isEqualTo(18);
  }

  @Test
  void D_without_transport_it_is_the_tour_capacity() {
    assertThat(VehicleCapacity.effective(18, false, 0)).isEqualTo(18);
    assertThat(VehicleCapacity.effective(18, false, 5)).isEqualTo(18);
  }

  @Test
  void D_with_transport_and_no_vehicle_nothing_can_be_sold() {
    assertThat(VehicleCapacity.effective(18, true, 0)).isZero();
  }

  @Test
  void D_the_total_of_the_vehicles_adds_their_capacities() {
    assertThat(VehicleCapacity.total(List.of(vehicle(1, 12), vehicle(2, 30), vehicle(3, 8)))).isEqualTo(50);
    assertThat(VehicleCapacity.total(List.of())).isZero();
  }
}
