package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Catalog;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ScheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Outbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The real engine, built by multi-tour-operations-cost-db and reached as operations_cost_app (norm 5.3, Annex J): the address
 * is TEST_DATABASE_URL, made by {@code scripts/prepare-test-db.sh}. Without it these tests are skipped; the CI fails if any was
 * skipped. Each test uses a tenant of its own, so none needs the tables empty (the application user cannot truncate them).
 */
abstract class PostgresTest {

  static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");

  static HikariDataSource dataSource;
  static JdbcClient jdbc;
  static JdbcDepartureRepository departures;
  static JdbcOutbox outbox;
  static JdbcIdempotencyKeys keys;
  static JdbcTransaction transaction;

  @BeforeAll
  static void connect() {
    String url = System.getenv("TEST_DATABASE_URL");
    assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL is not set: sh scripts/prepare-test-db.sh up");
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setMaximumPoolSize(10);
    dataSource = new HikariDataSource(config);
    jdbc = JdbcClient.create(dataSource);
    departures = new JdbcDepartureRepository(jdbc);
    outbox = new JdbcOutbox(jdbc, new ObjectMapper());
    keys = new JdbcIdempotencyKeys(jdbc);
    transaction = new JdbcTransaction(new DataSourceTransactionManager(dataSource));
  }

  @AfterAll
  static void disconnect() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  static DataSource pool() {
    return dataSource;
  }

  /** The use case over the real ports; the catalog says every tour is an active one of 12 seats. The clock ticks in microseconds, as the column keeps them. */
  static ScheduleDeparture useCase(Outbox events) {
    Catalog catalog = serviceId -> Optional.of(new CatalogTour(serviceId, CatalogTour.Type.TOUR, true, 12));
    return new ScheduleDeparture(departures, events, keys, catalog, transaction, UUID::randomUUID,
        Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000)));
  }

  static long count(String table, UUID tenant) {
    String column = table.equals("idempotency_key") ? "scope LIKE :t" : "tenant_id = CAST(:t AS uuid)";
    String value = table.equals("idempotency_key") ? "%" + tenant : tenant.toString();
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column).param("t", value).query(Long.class).single();
  }
}
