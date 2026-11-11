package co.edu.corhuila.multi_tour.operations_cost.app;

import co.edu.corhuila.multi_tour.operations_cost.adapters.catalog.HttpCatalog;
import co.edu.corhuila.multi_tour.operations_cost.adapters.catalog.HttpReservations;
import co.edu.corhuila.multi_tour.operations_cost.adapters.persistence.JdbcDepartureRepository;
import co.edu.corhuila.multi_tour.operations_cost.adapters.persistence.JdbcIdempotencyKeys;
import co.edu.corhuila.multi_tour.operations_cost.adapters.persistence.JdbcOutbox;
import co.edu.corhuila.multi_tour.operations_cost.adapters.persistence.JdbcOperationalCosts;
import co.edu.corhuila.multi_tour.operations_cost.adapters.persistence.JdbcOutboxEvents;
import co.edu.corhuila.multi_tour.operations_cost.adapters.persistence.JdbcServiceRecords;
import co.edu.corhuila.multi_tour.operations_cost.adapters.persistence.JdbcTransaction;
import co.edu.corhuila.multi_tour.operations_cost.adapters.persistence.JdbcVehicleKeys;
import co.edu.corhuila.multi_tour.operations_cost.adapters.persistence.JdbcVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.AddDepartureVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureViews;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.FinishDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.GetDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ListDepartures;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.StartDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Catalog;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureRepository;
import co.edu.corhuila.multi_tour.operations_cost.adapters.security.OutboxExceptionalAccessAudit;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdGenerator;
import co.edu.corhuila.multi_tour.operations_cost.core.security.ExceptionalAccessAudit;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdempotencyKeys;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.MarkPendingRegularization;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Outbox;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RegularizeDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RescheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ListCosts;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OperationalCosts;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RecordCost;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Reservations;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.SaveServices;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServiceRecords;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServicesReader;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ScheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Transaction;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.OutboxEvents;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.OutboxRelay;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.RetryPolicy;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.ListVehicles;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.RegisterVehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.VehicleKeys;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicles;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/** The composition root: the one place that knows every piece and builds them. */
@SpringBootApplication
@ComponentScan("co.edu.corhuila.multi_tour.operations_cost")
class CompositionRoot {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * The single instance of PostgreSQL, as the user of the domain (Annex J.4). The limits are the ones of Annex C and
   * ADR-004 D10: a pool of 10, 5 s to get a connection, 5 s per statement. The pool does not connect at start (a database
   * that is late must not stop the service), and a bad address is told without quoting it: it may carry a password.
   */
  @Bean(destroyMethod = "close")
  DataSource dataSource(
      @Value("${DATABASE_URL:}") String url,
      @Value("${DATABASE_USER:}") String user,
      @Value("${DATABASE_PASSWORD:}") String password) {
    if (url.isBlank() || !url.startsWith("jdbc:postgresql://")) {
      throw new IllegalStateException("DATABASE_URL is required: jdbc:postgresql://host:5432/database");
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user.isBlank() ? null : user);
    config.setPassword(password.isBlank() ? null : password);
    config.setMaximumPoolSize(10);
    config.setConnectionTimeout(5_000);
    config.setConnectionInitSql("SET statement_timeout = 5000");
    config.setInitializationFailTimeout(-1);
    return new HikariDataSource(config);
  }

  @Bean
  DepartureRepository departureRepository(JdbcClient jdbc) {
    return new JdbcDepartureRepository(jdbc);
  }

  @Bean
  Outbox outbox(JdbcClient jdbc, ObjectMapper json) {
    return new JdbcOutbox(jdbc, json);
  }

  @Bean
  ExceptionalAccessAudit exceptionalAccessAudit(Outbox outbox, IdGenerator ids, Clock clock) {
    return new OutboxExceptionalAccessAudit(outbox, ids, clock);
  }

  @Bean
  IdempotencyKeys idempotencyKeys(JdbcClient jdbc) {
    return new JdbcIdempotencyKeys(jdbc);
  }

  @Bean
  OperationalCosts operationalCosts(JdbcClient jdbc) {
    return new JdbcOperationalCosts(jdbc);
  }

  @Bean
  ServiceRecords serviceRecords(JdbcClient jdbc) {
    return new JdbcServiceRecords(jdbc);
  }

  @Bean
  Vehicles vehicles(JdbcClient jdbc) {
    return new JdbcVehicles(jdbc);
  }

  @Bean
  VehicleKeys vehicleKeys(JdbcClient jdbc) {
    return new JdbcVehicleKeys(jdbc);
  }

  @Bean
  Transaction transaction(DataSource dataSource) {
    return new JdbcTransaction(new DataSourceTransactionManager(dataSource));
  }

  /** The catalog of commercial-reservation, read with the credential of the request, 3 s at most (plan D-05). */
  @Bean
  Catalog catalog(@Value("${COMMERCIAL_RESERVATION_BASE_URL:}") String baseUrl, HttpCatalog.Credentials credentials) {
    return new HttpCatalog(baseUrl, Duration.ofSeconds(3), credentials);
  }

  /** The seats of a departure, read from the same service with the credential of the request (decision D2 of spec 002). */
  @Bean
  Reservations reservations(@Value("${COMMERCIAL_RESERVATION_BASE_URL:}") String baseUrl, HttpCatalog.Credentials credentials) {
    return new HttpReservations(baseUrl, Duration.ofSeconds(3), credentials);
  }

  @Bean
  RescheduleDeparture rescheduleDeparture(
      DepartureRepository departures, Outbox outbox, Reservations reservations, Catalog catalog, Vehicles vehicles,
      Transaction transaction, IdGenerator ids, Clock clock) {
    return new RescheduleDeparture(departures, outbox, reservations, catalog, vehicles, transaction, ids, clock);
  }

  @Bean
  OutboxEvents outboxEvents(JdbcClient jdbc, ObjectMapper json) {
    return new JdbcOutboxEvents(jdbc, json);
  }

  /** The wait between attempts and the number of them (ADR-004 D10), from the configuration. */
  @Bean
  RetryPolicy outboxRetryPolicy(
      @Value("${outbox.retry.base:PT5S}") Duration base, @Value("${outbox.retry.cap:PT15M}") Duration cap,
      @Value("${outbox.retry.max-attempts:20}") int maxAttempts) {
    // a bare number in the configuration is read as milliseconds, so a typo must not turn into a hammer
    if (base.compareTo(Duration.ofSeconds(1)) < 0 || cap.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalStateException("outbox.retry.base must be at least 1 s and outbox.retry.cap at most 24 h");
    }
    return new RetryPolicy(base, cap, maxAttempts);
  }

  @Bean
  OutboxRelay outboxRelay(OutboxEvents events, Clock clock, RetryPolicy policy) {
    return new OutboxRelay(events, clock, policy);
  }

  @Bean
  IdGenerator idGenerator() {
    return UUID::randomUUID;
  }

  @Bean
  ScheduleDeparture scheduleDeparture(
      DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, Catalog catalog, Vehicles vehicles,
      Transaction transaction, IdGenerator ids, Clock clock) {
    return new ScheduleDeparture(departures, outbox, keys, catalog, vehicles, transaction, ids, clock);
  }

  @Bean
  AddDepartureVehicles addDepartureVehicles(
      DepartureRepository departures, Vehicles vehicles, Outbox outbox, Catalog catalog, Transaction transaction, IdGenerator ids,
      Clock clock) {
    return new AddDepartureVehicles(departures, vehicles, outbox, catalog, transaction, ids, clock);
  }

  @Bean
  DepartureViews departureViews(Catalog catalog, Reservations reservations, Vehicles vehicles) {
    return new DepartureViews(catalog, reservations, vehicles);
  }

  @Bean
  ListDepartures listDepartures(DepartureRepository departures, DepartureViews views) {
    return new ListDepartures(departures, views);
  }

  @Bean
  ServicesReader servicesReader(Reservations reservations, ServiceRecords records) {
    return new ServicesReader(reservations, records);
  }

  @Bean
  GetDeparture getDeparture(DepartureRepository departures, DepartureViews views, ServicesReader services) {
    return new GetDeparture(departures, views, services);
  }

  @Bean
  RecordCost recordCost(
      DepartureRepository departures, OperationalCosts costs, ServiceRecords services, Catalog catalog, Outbox outbox, Transaction transaction,
      IdGenerator ids, Clock clock) {
    return new RecordCost(departures, costs, services, catalog, outbox, transaction, ids, clock);
  }

  @Bean
  ListCosts listCosts(DepartureRepository departures, OperationalCosts costs, ServiceRecords services, Catalog catalog) {
    return new ListCosts(departures, costs, services, catalog);
  }

  @Bean
  SaveServices saveServices(
      DepartureRepository departures, ServiceRecords records, IdempotencyKeys keys, ServicesReader reader, DepartureViews views,
      Transaction transaction, Clock clock) {
    return new SaveServices(departures, records, keys, reader, views, transaction, clock);
  }

  @Bean
  StartDeparture startDeparture(
      DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, DepartureViews views, Transaction transaction, IdGenerator ids,
      Clock clock) {
    return new StartDeparture(departures, outbox, keys, views, transaction, ids, clock);
  }

  @Bean
  FinishDeparture finishDeparture(
      DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, DepartureViews views, Transaction transaction, IdGenerator ids,
      Clock clock) {
    return new FinishDeparture(departures, outbox, keys, views, transaction, ids, clock);
  }

  @Bean
  RegularizeDeparture regularizeDeparture(
      DepartureRepository departures, Outbox outbox, IdempotencyKeys keys, DepartureViews views, Transaction transaction, IdGenerator ids,
      Clock clock) {
    return new RegularizeDeparture(departures, outbox, keys, views, transaction, ids, clock);
  }

  @Bean
  MarkPendingRegularization markPendingRegularization(
      DepartureRepository departures, Outbox outbox, Transaction transaction, IdGenerator ids, Clock clock) {
    return new MarkPendingRegularization(departures, outbox, transaction, ids, clock);
  }

  @Bean
  RegisterVehicle registerVehicle(Vehicles vehicles, VehicleKeys keys, Transaction transaction, IdGenerator ids, Clock clock) {
    return new RegisterVehicle(vehicles, keys, transaction, ids, clock);
  }

  @Bean
  ListVehicles listVehicles(Vehicles vehicles) {
    return new ListVehicles(vehicles);
  }

  public static void main(String[] args) {
    SpringApplication.run(CompositionRoot.class, args);
  }
}
