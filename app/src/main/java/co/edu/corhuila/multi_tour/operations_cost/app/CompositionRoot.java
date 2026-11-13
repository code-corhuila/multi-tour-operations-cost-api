package co.edu.corhuila.multi_tour.operations_cost.app;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
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

  public static void main(String[] args) {
    SpringApplication.run(CompositionRoot.class, args);
  }
}
