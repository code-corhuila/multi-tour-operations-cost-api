package co.edu.corhuila.multi_tour.operations_cost.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-OPS-002 (api spec 003), RN-OPS-API-30: a vehicle has a name of 1 to 60 characters and a capacity from 1 to 100. */
class VehicleTest {

  private static final UUID ID = new UUID(0, 1);
  private static final UUID TENANT = new UUID(0, 2);
  private static final UUID ACCOUNT = new UUID(0, 3);
  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

  private static Vehicle vehicle(String name, int capacity) {
    return new Vehicle(ID, TENANT, name, capacity, ACCOUNT, NOW);
  }

  @Test
  void D_vehicle_name_and_capacity_in_range() {
    Vehicle van = vehicle("Van VAN-204", 12);
    assertThat(van.name()).isEqualTo("Van VAN-204");
    assertThat(van.capacity()).isEqualTo(12);
    assertThat(vehicle("V", 1).capacity()).isEqualTo(1);
    assertThat(vehicle("n".repeat(60), 100).capacity()).isEqualTo(100);
  }

  @Test
  void D_the_name_is_kept_without_the_spaces_around_it() {
    assertThat(vehicle("  Van 1  ", 12).name()).isEqualTo("Van 1");
  }

  @Test
  void D_vehicle_out_of_range_is_refused() {
    assertThatThrownBy(() -> vehicle("Van", 0)).isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("capacity");
    assertThatThrownBy(() -> vehicle("Van", 101)).isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("capacity");
    assertThatThrownBy(() -> vehicle("", 12)).isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("name");
    assertThatThrownBy(() -> vehicle("   ", 12)).isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("name");
    assertThatThrownBy(() -> vehicle("n".repeat(61), 12)).isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("name");
    assertThatThrownBy(() -> vehicle(null, 12)).isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("name");
  }

  @Test
  void D_a_vehicle_needs_its_owner_and_its_author() {
    assertThatThrownBy(() -> new Vehicle(null, TENANT, "Van", 12, ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new Vehicle(ID, null, "Van", 12, ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new Vehicle(ID, TENANT, "Van", 12, null, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new Vehicle(ID, TENANT, "Van", 12, ACCOUNT, null)).isInstanceOf(InvalidDataException.class);
  }
}
