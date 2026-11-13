package co.edu.corhuila.multi_tour.operations_cost.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import javax.sql.DataSource;

/** The database limits of Annex C are named values of the composition root, and the service needs its address to start. */
class DatabaseConfigTest extends ShellTest {

  @Autowired DataSource dataSource;

  @Test
  void A_limits_are_named_properties_with_the_annex_values() {
    HikariDataSource pool = (HikariDataSource) dataSource;
    assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
    assertThat(pool.getConnectionTimeout()).isEqualTo(5_000);
    assertThat(pool.getConnectionInitSql()).contains("statement_timeout = 5000");
  }

  @Test
  void A_the_pool_does_not_connect_at_start_so_a_database_that_is_late_does_not_stop_the_service() {
    HikariDataSource pool = (HikariDataSource) dataSource;
    assertThat(pool.getInitializationFailTimeout()).isNegative();
  }

  @Test
  void C_start_fails_without_the_database_address() {
    assertThatThrownBy(() -> start(List.of())).hasStackTraceContaining("DATABASE_URL");
  }

  @Test
  void C_start_fails_with_an_address_that_is_not_jdbc_postgresql_and_does_not_quote_it() {
    assertThatThrownBy(() -> start(List.of("--DATABASE_URL=mysql://user:secret@host/db")))
        .hasStackTraceContaining("DATABASE_URL")
        .satisfies(failure -> assertThat(trace(failure)).doesNotContain("secret"));
  }

  private static String trace(Throwable failure) {
    java.io.StringWriter text = new java.io.StringWriter();
    failure.printStackTrace(new java.io.PrintWriter(text));
    return text.toString();
  }

  private static void start(List<String> properties) {
    List<String> args = new ArrayList<>(List.of("--server.port=0", "--DATABASE_URL=", "--JWT_PUBLIC_KEY=" + Tokens.publicKeyPem()));
    args.addAll(properties);
    SpringApplication.run(CompositionRoot.class, args.toArray(String[]::new)).close();
  }
}
