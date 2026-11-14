package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** The ports of the departure as in-memory fakes, with a transaction that puts everything back when it fails. */
final class FakeWorld implements DepartureRepository, Outbox, IdempotencyKeys, Catalog, Reservations, Transaction, IdGenerator {

  final Map<UUID, Departure> departures = new LinkedHashMap<>();
  final List<OutboxEvent> events = new ArrayList<>();
  final Map<String, UUID> keys = new HashMap<>();
  final Map<UUID, CatalogTour> tours = new HashMap<>();
  final Map<UUID, Reservations.Seats> seats = new HashMap<>();
  boolean reservationsDown;
  boolean updateRefused;
  int reservationCalls;
  boolean catalogDown;
  boolean outboxFails;
  UUID racedDeparture;
  private final Map<String, UUID> committedByOthers = new HashMap<>();
  int catalogCalls;
  private int ids;

  @Override
  public <T> T run(Supplier<T> work) {
    Map<UUID, Departure> departuresBefore = new LinkedHashMap<>(departures);
    List<OutboxEvent> eventsBefore = new ArrayList<>(events);
    Map<String, UUID> keysBefore = new HashMap<>(keys);
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
  public void add(OutboxEvent event) {
    if (outboxFails) {
      throw new IllegalStateException("the outbox is down");
    }
    events.add(event);
  }

  @Override
  public Optional<UUID> departureOf(UUID tenantId, String key) {
    return Optional.ofNullable(keys.get(tenantId + "/" + key));
  }

  @Override
  public void register(UUID tenantId, String key, UUID departureId) {
    if (racedDeparture != null) {
      // another request registered the key an instant ago and committed; this transaction rolls back
      committedByOthers.put(tenantId + "/" + key, racedDeparture);
      throw new DuplicateKeyException();
    }
    keys.put(tenantId + "/" + key, departureId);
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
  public UUID next() {
    return new UUID(0, ++ids);
  }
}
