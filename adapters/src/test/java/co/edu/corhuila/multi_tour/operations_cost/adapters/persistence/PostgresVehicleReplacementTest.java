package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Outbox;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ReplaceDepartureVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-OPS-002 part B (api spec 008, RN-OPS-API-74): taking vehicles off a departure and the replacement with its event, against the real engine. */
class PostgresVehicleReplacementTest extends PostgresTest {

  private static final Instant START = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS);
  private static final Instant END = START.plus(Duration.ofHours(8));

  private static Vehicle newVehicle(UUID tenant, String name, int capacity) {
    Vehicle created = new Vehicle(UUID.randomUUID(), tenant, name, capacity, ACCOUNT, Instant.now());
    vehicles.save(created);
    return created;
  }

  private static Departure scheduled(UUID tenant, String code) {
    Departure created = new Departure(UUID.randomUUID(), tenant, code, TOUR, START, END, "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, Instant.now());
    transaction.run(() -> {
      departures.save(created);
      return null;
    });
    return created;
  }

  private static void assign(Departure departure, Vehicle... vehicle) {
    transaction.run(() -> {
      vehicles.assign(departure.tenantId(), departure.id(), List.of(vehicle).stream().map(Vehicle::id).toList(), ACCOUNT, Instant.now());
      return null;
    });
  }

  private ReplaceDepartureVehicles over(Outbox events) {
    return new ReplaceDepartureVehicles(departures, vehicles, events, serviceId -> Optional.of(new CatalogTour(serviceId, CatalogTour.Type.TOUR, true, 18, true)),
        transaction, UUID::randomUUID, Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000)));
  }

  @Test
  void I_unassign_takes_off_only_those_vehicles_of_that_departure_and_tenant() {
    UUID tenant = UUID.randomUUID();
    Vehicle van = newVehicle(tenant, "Van 1", 12);
    Vehicle bus = newVehicle(tenant, "Bus", 30);
    Departure first = scheduled(tenant, "SAL-001");
    Departure second = scheduled(tenant, "SAL-002");
    assign(first, van, bus);
    assign(second, van);
    transaction.run(() -> {
      vehicles.unassign(UUID.randomUUID(), first.id(), List.of(van.id()));
      vehicles.unassign(tenant, first.id(), List.of(van.id()));
      return null;
    });
    assertThat(vehicles.ofDeparture(tenant, first.id())).extracting(Vehicle::name).containsExactly("Bus");
    assertThat(vehicles.ofDeparture(tenant, second.id())).extracting(Vehicle::name).containsExactly("Van 1");
  }

  @Test
  void I_replace_and_event_are_written_together_or_not_at_all() {
    UUID tenant = UUID.randomUUID();
    Vehicle van = newVehicle(tenant, "Van 1", 12);
    Vehicle bus = newVehicle(tenant, "Bus", 30);
    Departure departure = scheduled(tenant, "SAL-001");
    assign(departure, van);
    Outbox failing = new Outbox() {
      @Override
      public void add(OutboxEvent event) {
        throw new IllegalStateException("the outbox is down");
      }

      @Override
      public boolean waiting(UUID tenantId, UUID aggregateId, String eventType) {
        return false;
      }
    };
    assertThatThrownBy(() -> over(failing).execute(new ReplaceDepartureVehicles.Command(tenant, "SAL-001", List.of(bus.id()), "saga-step-0001", "trace-it")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(vehicles.ofDeparture(tenant, departure.id())).extracting(Vehicle::name).containsExactly("Van 1");
    over(outbox).execute(new ReplaceDepartureVehicles.Command(tenant, "SAL-001", List.of(bus.id()), "saga-step-0001", "trace-it"));
    assertThat(vehicles.ofDeparture(tenant, departure.id())).extracting(Vehicle::name).containsExactly("Bus");
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
  }
}
