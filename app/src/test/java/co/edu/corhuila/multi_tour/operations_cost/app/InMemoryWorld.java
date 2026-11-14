package co.edu.corhuila.multi_tour.operations_cost.app;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Catalog;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureRepository;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DependencyUnavailableException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdempotencyKeys;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Outbox;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ScheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RescheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Reservations;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Transaction;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.AddDepartureVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.BusinessRuleViolationException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.OutboxRelay;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.RetryPolicy;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.ListVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.RegisterVehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleKeys;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicles;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The ports of the departure in memory, in place of the database and of the catalog of another domain, so the route is tried
 * on its own: how the real adapters behave is the business of their own tests (the database, the catalog).
 */
final class InMemoryWorld
    implements DepartureRepository, Outbox, IdempotencyKeys, Catalog, Reservations, Transaction, Vehicles, VehicleKeys {

  /** One for the whole test run: the beans of the configuration below are this one. */
  static final InMemoryWorld WORLD = new InMemoryWorld();

  final Map<UUID, Departure> departures = new LinkedHashMap<>();
  final List<OutboxEvent> events = new ArrayList<>();
  final Map<String, UUID> keys = new HashMap<>();
  final Map<UUID, CatalogTour> tours = new HashMap<>();
  final Map<UUID, Reservations.Seats> seats = new HashMap<>();
  final Map<UUID, Vehicle> vehicleRows = new LinkedHashMap<>();
  final Map<UUID, Set<UUID>> assignments = new HashMap<>();
  final Map<String, UUID> vehicleKeys = new HashMap<>();
  volatile boolean reservationsDown;
  volatile boolean catalogDown;
  volatile int catalogCalls;

  void reset() {
    synchronized (this) {
      departures.clear();
      events.clear();
      keys.clear();
      vehicleRows.clear();
      assignments.clear();
      vehicleKeys.clear();
      tours.clear();
      catalogDown = false;
      seats.clear();
      reservationsDown = false;
      catalogCalls = 0;
    }
  }

  @Override
  public synchronized <T> T run(Supplier<T> work) {
    return work.get();
  }

  @Override
  public String nextCode(UUID tenantId) {
    return String.format("SAL-%03d", departures.values().stream().filter(d -> d.tenantId().equals(tenantId)).count() + 1);
  }

  @Override
  public void save(Departure departure) {
    departures.put(departure.id(), departure);
  }

  @Override
  public Optional<Departure> find(UUID tenantId, UUID id) {
    return Optional.ofNullable(departures.get(id)).filter(d -> d.tenantId().equals(tenantId));
  }

  @Override
  public Optional<Departure> findForUpdate(UUID tenantId, UUID id) {
    return find(tenantId, id);
  }

  @Override
  public Optional<Departure> findByCode(UUID tenantId, String code) {
    return departures.values().stream().filter(d -> d.tenantId().equals(tenantId) && d.code().equals(code)).findFirst();
  }

  @Override
  public boolean updateSchedule(Departure from, Departure to) {
    departures.put(to.id(), to);
    return true;
  }

  @Override
  public boolean waiting(UUID tenantId, UUID aggregateId, String eventType) {
    return events.stream().anyMatch(e -> e.tenantId().equals(tenantId) && e.aggregateId().equals(aggregateId) && e.eventType().equals(eventType));
  }

  @Override
  public void add(OutboxEvent event) {
    events.add(event);
  }

  @Override
  public Optional<UUID> departureOf(UUID tenantId, String key) {
    return Optional.ofNullable(keys.get(tenantId + "/" + key));
  }

  @Override
  public void register(UUID tenantId, String key, UUID departureId) {
    if (keys.putIfAbsent(tenantId + "/" + key, departureId) != null) {
      throw new DuplicateKeyException();
    }
  }

  @Override
  public Optional<Reservations.Seats> seatsOf(UUID serviceId, UUID departureId) {
    if (reservationsDown) {
      throw new DependencyUnavailableException("reservations");
    }
    return Optional.ofNullable(seats.get(departureId));
  }

  @Override
  public Optional<CatalogTour> tour(UUID serviceId) {
    catalogCalls++;
    if (catalogDown) {
      throw new DependencyUnavailableException("catalog");
    }
    return Optional.ofNullable(tours.get(serviceId));
  }

  @Override
  public void save(Vehicle vehicle) {
    if (nameTaken(vehicle.tenantId(), vehicle.name())) {
      throw new BusinessRuleViolationException("A vehicle with that name already exists");
    }
    vehicleRows.put(vehicle.id(), vehicle);
  }

  @Override
  public boolean nameTaken(UUID tenantId, String name) {
    return vehicleRows.values().stream().anyMatch(v -> v.tenantId().equals(tenantId) && v.name().equalsIgnoreCase(name.strip()));
  }

  @Override
  public Page<Vehicle> list(UUID tenantId, int page, int limit) {
    List<Vehicle> all = vehicleRows.values().stream().filter(v -> v.tenantId().equals(tenantId))
        .sorted(Comparator.comparing(v -> v.name().toLowerCase())).toList();
    int from = Math.min((page - 1) * limit, all.size());
    return new Page<>(all.subList(from, Math.min(from + limit, all.size())), page, limit, all.size());
  }

  @Override
  public List<Vehicle> lockAll(UUID tenantId, Collection<UUID> vehicleIds) {
    return vehicleIds.stream().sorted().map(vehicleRows::get).filter(v -> v != null && v.tenantId().equals(tenantId)).toList();
  }

  @Override
  public List<Vehicle> ofDeparture(UUID tenantId, UUID departureId) {
    return assignments.getOrDefault(departureId, Set.of()).stream().map(vehicleRows::get)
        .filter(v -> v != null && v.tenantId().equals(tenantId)).toList();
  }

  @Override
  public Optional<String> overlapping(UUID tenantId, Collection<UUID> vehicleIds, Instant start, Instant end, UUID except) {
    return assignments.entrySet().stream()
        .filter(e -> !e.getKey().equals(except) && e.getValue().stream().anyMatch(vehicleIds::contains))
        .map(e -> departures.get(e.getKey()))
        .filter(d -> d != null && d.tenantId().equals(tenantId)
            && (d.status() == DepartureStatus.SCHEDULED || d.status() == DepartureStatus.IN_EXECUTION)
            && start.isBefore(d.estimatedEnd()) && d.start().isBefore(end))
        .map(Departure::code).findFirst();
  }

  @Override
  public void assign(UUID tenantId, UUID departureId, Collection<UUID> vehicleIds, UUID by, Instant at) {
    assignments.computeIfAbsent(departureId, id -> new LinkedHashSet<>()).addAll(vehicleIds);
  }

  @Override
  public Optional<UUID> vehicleOf(UUID tenantId, String key) {
    return Optional.ofNullable(vehicleKeys.get(tenantId + "/" + key));
  }

  @Override
  public void registerVehicle(UUID tenantId, String key, UUID vehicleId) {
    if (vehicleKeys.putIfAbsent(tenantId + "/" + key, vehicleId) != null) {
      throw new DuplicateKeyException();
    }
  }

  /** Replaces the use case of the composition root by one over these ports, for the test that imports it. */
  @TestConfiguration
  static class Config {

    @Bean
    @Primary
    ScheduleDeparture testScheduleDeparture() {
      return new ScheduleDeparture(WORLD, WORLD, WORLD, WORLD, WORLD, WORLD, UUID::randomUUID, Clock.systemUTC());
    }

    @Bean
    @Primary
    OutboxRelay testOutboxRelay() {
      return new OutboxRelay(InMemoryOutboxEvents.STORE, Clock.systemUTC(),
          new RetryPolicy(java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(15), 20));
    }

    @Bean
    @Primary
    RescheduleDeparture testRescheduleDeparture() {
      return new RescheduleDeparture(WORLD, WORLD, WORLD, WORLD, WORLD, WORLD, UUID::randomUUID, Clock.systemUTC());
    }

    @Bean
    @Primary
    AddDepartureVehicles testAddDepartureVehicles() {
      return new AddDepartureVehicles(WORLD, WORLD, WORLD, WORLD, WORLD, UUID::randomUUID, Clock.systemUTC());
    }

    @Bean
    @Primary
    RegisterVehicle testRegisterVehicle() {
      return new RegisterVehicle(WORLD, WORLD, WORLD, UUID::randomUUID, Clock.systemUTC());
    }

    @Bean
    @Primary
    ListVehicles testListVehicles() {
      return new ListVehicles(WORLD);
    }
  }
}
