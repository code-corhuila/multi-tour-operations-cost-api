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
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.OutboxRelay;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.RetryPolicy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The ports of the departure in memory, in place of the database and of the catalog of another domain, so the route is tried
 * on its own: how the real adapters behave is the business of their own tests (the database, the catalog).
 */
final class InMemoryWorld implements DepartureRepository, Outbox, IdempotencyKeys, Catalog, Reservations, Transaction {

  /** One for the whole test run: the beans of the configuration below are this one. */
  static final InMemoryWorld WORLD = new InMemoryWorld();

  final Map<UUID, Departure> departures = new LinkedHashMap<>();
  final List<OutboxEvent> events = new ArrayList<>();
  final Map<String, UUID> keys = new HashMap<>();
  final Map<UUID, CatalogTour> tours = new HashMap<>();
  final Map<UUID, Reservations.Seats> seats = new HashMap<>();
  volatile boolean reservationsDown;
  volatile boolean catalogDown;
  volatile int catalogCalls;

  void reset() {
    synchronized (this) {
      departures.clear();
      events.clear();
      keys.clear();
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

  /** Replaces the use case of the composition root by one over these ports, for the test that imports it. */
  @TestConfiguration
  static class Config {

    @Bean
    @Primary
    ScheduleDeparture testScheduleDeparture() {
      return new ScheduleDeparture(WORLD, WORLD, WORLD, WORLD, WORLD, UUID::randomUUID, Clock.systemUTC());
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
      return new RescheduleDeparture(WORLD, WORLD, WORLD, WORLD, WORLD, UUID::randomUUID, Clock.systemUTC());
    }
  }
}
