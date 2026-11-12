package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.AddDepartureVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.BusinessRuleViolationException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Cancellation;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Outbox;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The vehicles against the real engine, with the schema that multi-tour-operations-cost-db builds (api spec 003, RN-OPS-API-31,
 * -33, -36): the unique name, the key, the overlap query, and two assignments of one vehicle at the same time.
 */
class PostgresVehiclesTest extends PostgresTest {

  private static final Instant START = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS);
  private static final Instant END = START.plus(Duration.ofHours(8));

  private static Vehicle newVehicle(UUID tenant, String name, int capacity) {
    return new Vehicle(UUID.randomUUID(), tenant, name, capacity, ACCOUNT, Instant.now());
  }

  private static Departure departure(UUID tenant, String code, Instant start, Instant end, DepartureStatus status) {
    // a cancelled departure is made the way the service makes it: scheduled first, then cancelled (the engine refuses a cancelled one without its instant)
    Departure created = new Departure(UUID.randomUUID(), tenant, code, TOUR, start, end, "America/Bogota",
        status == DepartureStatus.CANCELLED ? DepartureStatus.SCHEDULED : status, ACCOUNT, Instant.now());
    transaction.run(() -> {
      departures.save(created);
      return null;
    });
    if (status != DepartureStatus.CANCELLED) {
      return created;
    }
    Departure cancelled = created.cancel(Cancellation.Cause.NOT_ATTRIBUTABLE_TO_CUSTOMER, null, null, Instant.now());
    assertThat(departures.updateExecution(created, cancelled)).isTrue();
    return cancelled;
  }

  private static void assign(Departure departure, Vehicle... vehicle) {
    transaction.run(() -> {
      vehicles.assign(departure.tenantId(), departure.id(), List.of(vehicle).stream().map(Vehicle::id).toList(), ACCOUNT, Instant.now());
      return null;
    });
  }

  @Test
  void I_a_vehicle_is_saved_read_and_listed_by_tenant_sorted_by_name() {
    UUID tenant = UUID.randomUUID();
    Vehicle bus = newVehicle(tenant, "bus", 30);
    Vehicle boat = newVehicle(tenant, "Boat", 10);
    Vehicle foreign = newVehicle(UUID.randomUUID(), "Van", 12);
    for (Vehicle v : List.of(bus, boat, foreign)) {
      vehicles.save(v);
    }
    var page = vehicles.list(tenant, 1, 10);
    assertThat(page.data()).extracting(Vehicle::name).containsExactly("Boat", "bus");
    assertThat(page.total()).isEqualTo(2);
    assertThat(vehicles.list(tenant, 2, 1).data()).extracting(Vehicle::name).containsExactly("bus");
    assertThat(vehicles.lockAll(tenant, List.of(bus.id(), boat.id(), foreign.id()))).extracting(Vehicle::id)
        .containsExactlyInAnyOrder(bus.id(), boat.id());
  }

  @Test
  void I_same_name_in_the_tenant_is_refused_ignoring_case_and_spaces_but_not_in_another_tenant() {
    UUID tenant = UUID.randomUUID();
    vehicles.save(newVehicle(tenant, "Van 1", 12));
    assertThat(vehicles.nameTaken(tenant, "  VAN 1 ")).isTrue();
    assertThat(vehicles.nameTaken(UUID.randomUUID(), "Van 1")).isFalse();
    assertThatThrownBy(() -> vehicles.save(newVehicle(tenant, "van 1", 5))).isInstanceOf(BusinessRuleViolationException.class);
    vehicles.save(newVehicle(UUID.randomUUID(), "Van 1", 12));
  }

  @Test
  void I_the_key_of_a_vehicle_is_read_back_and_a_second_registration_is_a_duplicate() {
    UUID tenant = UUID.randomUUID();
    Vehicle van = newVehicle(tenant, "Van 1", 12);
    vehicles.save(van);
    assertThat(vehicleKeys.vehicleOf(tenant, "key-0001-abcdef")).isEmpty();
    vehicleKeys.registerVehicle(tenant, "key-0001-abcdef", van.id());
    assertThat(vehicleKeys.vehicleOf(tenant, "key-0001-abcdef")).contains(van.id());
    assertThat(vehicleKeys.vehicleOf(UUID.randomUUID(), "key-0001-abcdef")).isEmpty();
    assertThatThrownBy(() -> vehicleKeys.registerVehicle(tenant, "key-0001-abcdef", van.id())).isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void I_the_overlap_names_the_other_departure_touching_ends_do_not_count_and_closed_departures_free_the_vehicle() {
    UUID tenant = UUID.randomUUID();
    Vehicle van = newVehicle(tenant, "Van 1", 12);
    vehicles.save(van);
    Departure scheduled = departure(tenant, "SAL-001", START, END, DepartureStatus.SCHEDULED);
    assign(scheduled, van);
    assertThat(vehicles.overlapping(tenant, List.of(van.id()), START.plus(Duration.ofHours(2)), END.plus(Duration.ofHours(2)), UUID.randomUUID()))
        .contains("SAL-001");
    assertThat(vehicles.overlapping(tenant, List.of(van.id()), END, END.plus(Duration.ofHours(8)), UUID.randomUUID())).isEmpty();
    assertThat(vehicles.overlapping(tenant, List.of(van.id()), START.minus(Duration.ofHours(8)), START, UUID.randomUUID())).isEmpty();
    assertThat(vehicles.overlapping(tenant, List.of(van.id()), START, END, scheduled.id())).isEmpty();
    assertThat(vehicles.overlapping(UUID.randomUUID(), List.of(van.id()), START, END, UUID.randomUUID())).isEmpty();
    Departure cancelled = departure(tenant, "SAL-002", START.plus(Duration.ofDays(2)), END.plus(Duration.ofDays(2)), DepartureStatus.CANCELLED);
    assign(cancelled, van);
    assertThat(vehicles.overlapping(tenant, List.of(van.id()), START.plus(Duration.ofDays(2)), END.plus(Duration.ofDays(2)), UUID.randomUUID())).isEmpty();
  }

  @Test
  void I_the_vehicles_of_a_departure_are_read_and_assigning_twice_changes_nothing() {
    UUID tenant = UUID.randomUUID();
    Vehicle van = newVehicle(tenant, "Van 1", 12);
    Vehicle bus = newVehicle(tenant, "Bus", 30);
    vehicles.save(van);
    vehicles.save(bus);
    Departure scheduled = departure(tenant, "SAL-001", START, END, DepartureStatus.SCHEDULED);
    assign(scheduled, van, bus);
    assign(scheduled, van);
    assertThat(vehicles.ofDeparture(tenant, scheduled.id())).extracting(Vehicle::name).containsExactly("Bus", "Van 1");
    assertThat(vehicles.ofDeparture(UUID.randomUUID(), scheduled.id())).isEmpty();
  }

  private AddDepartureVehicles addOver(Outbox events) {
    return new AddDepartureVehicles(departures, vehicles, events, serviceId -> Optional.of(new CatalogTour(serviceId, CatalogTour.Type.TOUR, true, 18, true)),
        transaction, UUID::randomUUID, Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000)));
  }

  private static Caller admin(UUID tenant) {
    return new Caller(ACCOUNT.toString(), tenant.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
  }

  @Test
  void I_vehicles_and_event_are_written_together_or_not_at_all() {
    UUID tenant = UUID.randomUUID();
    Vehicle van = newVehicle(tenant, "Van 1", 12);
    vehicles.save(van);
    Departure scheduled = departure(tenant, "SAL-001", START, END, DepartureStatus.SCHEDULED);
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
    assertThatThrownBy(() -> addOver(failing).execute(new AddDepartureVehicles.Command(admin(tenant), "SAL-001", List.of(van.id()), "trace-it")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(vehicles.ofDeparture(tenant, scheduled.id())).isEmpty();
    addOver(outbox).execute(new AddDepartureVehicles.Command(admin(tenant), "SAL-001", List.of(van.id()), "trace-it"));
    assertThat(vehicles.ofDeparture(tenant, scheduled.id())).hasSize(1);
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
  }

  @Test
  void I_two_assignments_at_once_only_one_wins() throws Exception {
    UUID tenant = UUID.randomUUID();
    Vehicle van = newVehicle(tenant, "Van 1", 12);
    vehicles.save(van);
    departure(tenant, "SAL-001", START, END, DepartureStatus.SCHEDULED);
    departure(tenant, "SAL-002", START.plus(Duration.ofHours(1)), END.plus(Duration.ofHours(1)), DepartureStatus.SCHEDULED);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Callable<String>> work = List.of("SAL-001", "SAL-002").stream().<Callable<String>>map(code -> () -> {
        go.await();
        try {
          addOver(outbox).execute(new AddDepartureVehicles.Command(admin(tenant), code, List.of(van.id()), "trace-it"));
          return "won";
        } catch (BusinessRuleViolationException refused) {
          return "refused";
        }
      }).toList();
      List<Future<String>> running = work.stream().map(pool::submit).toList();
      go.countDown();
      List<String> outcomes = new java.util.ArrayList<>();
      for (Future<String> done : running) {
        outcomes.add(done.get());
      }
      assertThat(outcomes).containsExactlyInAnyOrder("won", "refused");
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
  }

  @Test
  void I_the_lock_of_a_departure_makes_a_second_request_wait_until_the_first_one_ends() throws Exception {
    UUID tenant = UUID.randomUUID();
    Departure scheduled = departure(tenant, "SAL-001", START, END, DepartureStatus.SCHEDULED);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicLong secondLocked = new java.util.concurrent.atomic.AtomicLong();
    java.util.concurrent.atomic.AtomicLong firstEnded = new java.util.concurrent.atomic.AtomicLong();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(() -> transaction.run(() -> {
        departures.findForUpdate(tenant, scheduled.id());
        holding.countDown();
        try {
          release.await();
          Thread.sleep(300);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        firstEnded.set(System.nanoTime());
        return null;
      }));
      holding.await();
      Future<?> second = pool.submit(() -> transaction.run(() -> {
        departures.findForUpdate(tenant, scheduled.id());
        secondLocked.set(System.nanoTime());
        return null;
      }));
      release.countDown();
      first.get();
      second.get();
    } finally {
      pool.shutdownNow();
    }
    assertThat(secondLocked.get()).isGreaterThanOrEqualTo(firstEnded.get());
  }
}
