package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleKeys;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicles;
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

/** The ports of the departure as in-memory fakes, with a transaction that puts everything back when it fails. */
final class FakeWorld
    implements DepartureRepository, Outbox, IdempotencyKeys, Catalog, Reservations, Transaction, IdGenerator, Vehicles, VehicleKeys, ServiceRecords, OperationalCosts, ExtraordinaryCancellations {

  final Map<UUID, Departure> departures = new LinkedHashMap<>();
  final List<OutboxEvent> events = new ArrayList<>();
  final Map<String, UUID> keys = new HashMap<>();
  final Map<UUID, CatalogTour> tours = new HashMap<>();
  final Map<UUID, Reservations.Seats> seats = new HashMap<>();
  boolean reservationsDown;
  boolean updateRefused;
  int reservationCalls;
  boolean catalogDown;
  /** Runs when a request takes the lock of a departure: whatever another request committed before it was granted. */
  Runnable onLock;
  final Map<UUID, Vehicle> vehicleRows = new LinkedHashMap<>();
  final Map<UUID, Set<UUID>> assignments = new HashMap<>();
  final Map<String, UUID> vehicleKeys = new HashMap<>();
  boolean outboxFails;
  /** The reservations commercial-reservation holds for a departure, by departure id. */
  final Map<UUID, List<ReservationOfDeparture>> reservationRows = new HashMap<>();
  /** What was recorded: departure id, then reservation id. */
  final Map<UUID, Map<UUID, ServiceRecord>> serviceRows = new HashMap<>();
  final Map<UUID, UUID> servicesRecordedBy = new HashMap<>();
  /** The extraordinary cancellations: departure id, then reservation id. */
  final Map<UUID, Map<UUID, ExtraordinaryCancellation>> cancellationRows = new HashMap<>();
  /** The cost lines in the order recorded, and the line each key of creation made. */
  final List<OperationalCost> costRows = new ArrayList<>();
  final Map<String, OperationalCost> costKeys = new HashMap<>();
  UUID racedDeparture;
  private final Map<String, UUID> committedByOthers = new HashMap<>();
  // what another request committed while this one waited for the lock: a rollback of this one must not undo it
  private final Map<UUID, Departure> departuresCommittedByOthers = new HashMap<>();
  private final Map<String, UUID> keysCommittedByOthers = new HashMap<>();
  private final Map<UUID, Map<UUID, ExtraordinaryCancellation>> cancellationsCommittedByOthers = new HashMap<>();
  private final List<OutboxEvent> eventsCommittedByOthers = new ArrayList<>();
  int catalogCalls;
  private int ids;

  @Override
  public <T> T run(Supplier<T> work) {
    Map<UUID, Departure> departuresBefore = new LinkedHashMap<>(departures);
    List<OutboxEvent> eventsBefore = new ArrayList<>(events);
    Map<String, UUID> keysBefore = new HashMap<>(keys);
    Map<UUID, Vehicle> vehiclesBefore = new LinkedHashMap<>(vehicleRows);
    Map<UUID, Set<UUID>> assignmentsBefore = new HashMap<>();
    assignments.forEach((departure, ids) -> assignmentsBefore.put(departure, new LinkedHashSet<>(ids)));
    Map<String, UUID> vehicleKeysBefore = new HashMap<>(vehicleKeys);
    List<OperationalCost> costsBefore = new ArrayList<>(costRows);
    Map<String, OperationalCost> costKeysBefore = new HashMap<>(costKeys);
    Map<UUID, Map<UUID, ServiceRecord>> servicesBefore = new HashMap<>();
    serviceRows.forEach((departure, rows) -> servicesBefore.put(departure, new LinkedHashMap<>(rows)));
    Map<UUID, Map<UUID, ExtraordinaryCancellation>> cancellationsBefore = new HashMap<>();
    cancellationRows.forEach((departure, rows) -> cancellationsBefore.put(departure, new LinkedHashMap<>(rows)));
    try {
      return work.get();
    } catch (RuntimeException failed) {
      departures.clear();
      departures.putAll(departuresBefore);
      events.clear();
      events.addAll(eventsBefore);
      keys.clear();
      keys.putAll(keysBefore);
      keys.putAll(committedByOthers);
      departures.putAll(departuresCommittedByOthers);
      keys.putAll(keysCommittedByOthers);
      vehicleRows.clear();
      vehicleRows.putAll(vehiclesBefore);
      assignments.clear();
      assignments.putAll(assignmentsBefore);
      vehicleKeys.clear();
      vehicleKeys.putAll(vehicleKeysBefore);
      serviceRows.clear();
      serviceRows.putAll(servicesBefore);
      cancellationRows.clear();
      cancellationRows.putAll(cancellationsBefore);
      cancellationRows.putAll(cancellationsCommittedByOthers);
      eventsCommittedByOthers.stream().filter(e -> !events.contains(e)).forEach(events::add);
      costRows.clear();
      costRows.addAll(costsBefore);
      costKeys.clear();
      costKeys.putAll(costKeysBefore);
      throw failed;
    }
  }

  @Override
  public String nextCode(UUID tenantId) {
    long count = departures.values().stream().filter(d -> d.tenantId().equals(tenantId)).count();
    return String.format("SAL-%03d", count + 1);
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
    if (onLock != null) {
      Runnable concurrent = onLock;
      onLock = null;
      concurrent.run();
      departuresCommittedByOthers.putAll(departures);
      keysCommittedByOthers.putAll(keys);
      cancellationRows.forEach((departure, rows) -> cancellationsCommittedByOthers.put(departure, new LinkedHashMap<>(rows)));
      eventsCommittedByOthers.addAll(events);
    }
    return find(tenantId, id);
  }

  @Override
  public Optional<Departure> findByCode(UUID tenantId, String code) {
    return departures.values().stream().filter(d -> d.tenantId().equals(tenantId) && d.code().equals(code)).findFirst();
  }

  @Override
  public boolean updateSchedule(Departure from, Departure to) {
    if (updateRefused || !departures.getOrDefault(from.id(), to).equals(from)) {
      return false;
    }
    departures.put(to.id(), to);
    return true;
  }

  @Override
  public List<Departure> lockOpenPastEnd(Instant now, int limit) {
    return departures.values().stream()
        .filter(d -> (d.status() == DepartureStatus.SCHEDULED || d.status() == DepartureStatus.IN_EXECUTION)
            && !d.execution().pendingRegularization() && !d.estimatedEnd().isAfter(now))
        .sorted(Comparator.comparing(Departure::estimatedEnd).thenComparing(Departure::id))
        .limit(limit)
        .toList();
  }

  @Override
  public boolean updateExecution(Departure from, Departure to) {
    Departure current = departures.get(from.id());
    if (current == null || !current.tenantId().equals(from.tenantId()) || current.status() != from.status()) {
      return false;
    }
    departures.put(to.id(), to);
    return true;
  }

  @Override
  public co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page<Departure> page(
      UUID tenantId, Optional<DepartureStatus> status, int page, int limit) {
    List<Departure> matching = departures.values().stream()
        .filter(d -> d.tenantId().equals(tenantId) && status.map(s -> s == d.status()).orElse(true))
        .sorted(Comparator.<Departure, Instant>comparing(Departure::start).thenComparing(Departure::code).reversed())
        .toList();
    int from = Math.min((page - 1) * limit, matching.size());
    return new co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page<>(
        matching.subList(from, Math.min(from + limit, matching.size())), page, limit, matching.size());
  }

  @Override
  public boolean waiting(UUID tenantId, UUID aggregateId, String eventType) {
    return events.stream().anyMatch(e -> e.tenantId().equals(tenantId) && e.aggregateId().equals(aggregateId) && e.eventType().equals(eventType));
  }

  @Override
  public Optional<Reservations.Seats> seatsOf(UUID serviceId, UUID departureId) {
    reservationCalls++;
    if (reservationsDown) {
      throw new DependencyUnavailableException("reservations");
    }
    return Optional.ofNullable(seats.get(departureId));
  }

  @Override
  public java.util.Map<UUID, Reservations.Seats> seatsOfTour(UUID serviceId) {
    reservationCalls++;
    if (reservationsDown) {
      throw new DependencyUnavailableException("reservations");
    }
    return java.util.Map.copyOf(seats);
  }

  @Override
  public void add(OutboxEvent event) {
    if (outboxFails) {
      throw new IllegalStateException("the outbox is down");
    }
    events.add(event);
  }

  @Override
  public Optional<UUID> departureOf(UUID tenantId, String operation, String key) {
    return Optional.ofNullable(keys.get(tenantId + "/" + operation + "/" + key));
  }

  @Override
  public void register(UUID tenantId, String operation, String key, UUID departureId) {
    if (racedDeparture != null) {
      // another request registered the key an instant ago and committed; this transaction rolls back
      committedByOthers.put(tenantId + "/" + operation + "/" + key, racedDeparture);
      throw new DuplicateKeyException();
    }
    keys.put(tenantId + "/" + operation + "/" + key, departureId);
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
    vehicleRows.put(vehicle.id(), vehicle);
  }

  @Override
  public boolean nameTaken(UUID tenantId, String name) {
    return vehicleRows.values().stream()
        .anyMatch(v -> v.tenantId().equals(tenantId) && v.name().equalsIgnoreCase(name.strip()));
  }

  @Override
  public Page<Vehicle> list(UUID tenantId, int page, int limit) {
    List<Vehicle> all = vehicleRows.values().stream().filter(v -> v.tenantId().equals(tenantId))
        .sorted(Comparator.comparing(v -> v.name().toLowerCase())).toList();
    int from = Math.min((page - 1) * limit, all.size());
    return new Page<>(all.subList(from, Math.min(from + limit, all.size())), page, limit, all.size());
  }

  @Override
  public List<Vehicle> lockAll(UUID tenantId, Collection<UUID> ids) {
    return ids.stream().sorted().map(vehicleRows::get).filter(v -> v != null && v.tenantId().equals(tenantId)).toList();
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
  public void unassign(UUID tenantId, UUID departureId, Collection<UUID> vehicleIds) {
    assignments.getOrDefault(departureId, new LinkedHashSet<>()).removeAll(vehicleIds);
  }

  @Override
  public Optional<UUID> vehicleOf(UUID tenantId, String key) {
    return Optional.ofNullable(vehicleKeys.get(tenantId + "/" + key));
  }

  @Override
  public void registerVehicle(UUID tenantId, String key, UUID vehicleId) {
    vehicleKeys.put(tenantId + "/" + key, vehicleId);
  }

  @Override
  public List<ReservationOfDeparture> reservationsOf(UUID serviceId, UUID departureId) {
    reservationCalls++;
    if (reservationsDown) {
      throw new DependencyUnavailableException("reservations");
    }
    return reservationRows.getOrDefault(departureId, List.of());
  }

  @Override
  public void saveAll(UUID tenantId, UUID departureId, Collection<ServiceRecord> records, UUID by, Instant at) {
    Map<UUID, ServiceRecord> rows = serviceRows.computeIfAbsent(departureId, id -> new LinkedHashMap<>());
    records.forEach(record -> rows.put(record.reservationId(), record));
    servicesRecordedBy.put(departureId, by);
  }

  @Override
  public List<ServiceRecord> recordedFor(UUID tenantId, UUID departureId) {
    return List.copyOf(serviceRows.getOrDefault(departureId, Map.of()).values());
  }

  @Override
  public void add(UUID tenantId, UUID departureId, ExtraordinaryCancellation cancellation) {
    cancellationRows.computeIfAbsent(departureId, id -> new LinkedHashMap<>()).put(cancellation.reservationId(), cancellation);
  }

  @Override
  public boolean exists(UUID tenantId, UUID departureId, UUID reservationId) {
    return cancellationRows.getOrDefault(departureId, Map.of()).containsKey(reservationId);
  }

  @Override
  public List<ExtraordinaryCancellation> cancellationsOf(UUID tenantId, UUID departureId) {
    return List.copyOf(cancellationRows.getOrDefault(departureId, Map.of()).values());
  }

  @Override
  public void add(OperationalCost cost, String idempotencyKey) {
    String key = cost.tenantId() + "/" + idempotencyKey;
    if (costKeys.containsKey(key)) {
      throw new DuplicateKeyException();
    }
    costRows.add(cost);
    costKeys.put(key, cost);
  }

  @Override
  public Optional<OperationalCost> findByKey(UUID tenantId, String idempotencyKey) {
    return Optional.ofNullable(costKeys.get(tenantId + "/" + idempotencyKey));
  }

  @Override
  public List<OperationalCost> costsOf(UUID tenantId, UUID departureId) {
    return costRows.stream().filter(c -> c.tenantId().equals(tenantId) && c.departureId().equals(departureId)).toList();
  }

  @Override
  public UUID next() {
    return new UUID(0, ++ids);
  }
}
