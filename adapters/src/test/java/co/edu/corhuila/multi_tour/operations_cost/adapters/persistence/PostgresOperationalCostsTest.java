package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CostMode;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OperationalCost;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** The operational costs over the real engine (api spec 007 S7-03, RN-OPS-API-75 and -76, AC-API-07.12). */
class PostgresOperationalCostsTest extends PostgresTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final UUID RECORDER = UUID.fromString("00000000-0000-4000-8000-0000000000dd");

  private static JdbcOperationalCosts costs() {
    return new JdbcOperationalCosts(jdbc);
  }

  private static Departure running(UUID tenant) {
    Instant start = NOW.minus(Duration.ofHours(3));
    Departure created = new Departure(UUID.randomUUID(), tenant, departures.nextCode(tenant), TOUR, start, start.plus(Duration.ofHours(8)),
        "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, start.minus(Duration.ofDays(2)));
    departures.save(created);
    Departure started = created.start(NOW.minus(Duration.ofHours(2)), RECORDER, NOW);
    departures.updateExecution(created, started);
    return started;
  }

  private static OperationalCost expense(UUID tenant, Departure departure, long cents) {
    return OperationalCost.manual(UUID.randomUUID(), tenant, departure.id(), "Fuel of the boat", cents, true, null, RECORDER, NOW);
  }

  private static OperationalCost calculated(UUID tenant, Departure departure) {
    return OperationalCost.calculated(UUID.randomUUID(), tenant, departure.id(), "Boat and guide", 21_000_000L, 4, RECORDER, NOW.plusSeconds(1));
  }

  private static OutboxEvent eventOf(OperationalCost cost) {
    return new OutboxEvent(UUID.randomUUID(), "OperationalCostRecorded", cost.departureId(), cost.tenantId(), RECORDER, "trace-7",
        Map.of("costId", cost.costId().toString(), "amountCents", cost.amountCents()), NOW);
  }

  private static long rows(String table, UUID tenant) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :tenant").param("tenant", tenant).query(Long.class).single();
  }

  @Test
  void I_the_key_and_the_line_are_one_row_and_a_line_reads_back_whole() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    OperationalCost manual = OperationalCost.manual(UUID.randomUUID(), tenant, departure.id(), "Paid at the dock", 50_000L, false, "No catalogued cost", RECORDER, NOW);
    OperationalCost calc = calculated(tenant, departure);
    transaction.run(() -> {
      costs().add(manual, "cost-key-0001");
      costs().add(calc, "cost-key-0002");
      return null;
    });
    assertThat(costs().findByKey(tenant, "cost-key-0001")).contains(manual);
    assertThat(costs().findByKey(tenant, "cost-key-0002")).contains(calc);
    assertThat(costs().costsOf(tenant, departure.id())).containsExactly(manual, calc);
    assertThat(calc.mode()).isEqualTo(CostMode.CALCULATED);
    assertThat(rows("salida_cost", tenant)).isEqualTo(2);
  }

  @Test
  void I_cost_and_event_are_written_together() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    OperationalCost failing = expense(tenant, departure, 10_000L);
    assertThatThrownBy(() -> transaction.run(() -> {
      costs().add(failing, "cost-key-0001");
      outbox.add(eventOf(failing));
      throw new IllegalStateException("the outbox is down");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(rows("salida_cost", tenant)).isZero();
    assertThat(rows("outbox_event", tenant)).isZero();
    OperationalCost fine = expense(tenant, departure, 20_000L);
    transaction.run(() -> {
      costs().add(fine, "cost-key-0001");
      outbox.add(eventOf(fine));
      return null;
    });
    assertThat(rows("salida_cost", tenant)).isEqualTo(1);
    assertThat(rows("outbox_event", tenant)).isEqualTo(1);
  }

  @Test
  void I_a_key_used_again_in_the_tenant_is_refused_and_in_another_tenant_it_is_another_key() {
    UUID tenant = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    Departure departure = running(tenant);
    Departure strangers = running(other);
    transaction.run(() -> {
      costs().add(expense(tenant, departure, 10_000L), "cost-key-0001");
      return null;
    });
    assertThatThrownBy(() -> transaction.run(() -> {
      costs().add(expense(tenant, departure, 20_000L), "cost-key-0001");
      return null;
    })).isInstanceOf(DuplicateKeyException.class);
    transaction.run(() -> {
      costs().add(expense(other, strangers, 30_000L), "cost-key-0001");
      return null;
    });
    assertThat(costs().findByKey(other, "cost-key-0001")).isPresent();
    assertThat(costs().findByKey(tenant, "cost-key-0001").orElseThrow().amountCents()).isEqualTo(10_000L);
    assertThat(costs().costsOf(other, departure.id())).isEmpty();
  }

  @Test
  void I_two_costs_with_the_same_key_leave_one_line() throws Exception {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> attempt = () -> {
        go.await();
        try {
          transaction.run(() -> {
            costs().add(expense(tenant, departure, 10_000L), "cost-key-race");
            return null;
          });
          return true;
        } catch (DuplicateKeyException lost) {
          return false;
        }
      };
      Future<Boolean> one = pool.submit(attempt);
      Future<Boolean> two = pool.submit(attempt);
      go.countDown();
      assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(true, false);
    } finally {
      pool.shutdownNow();
    }
    assertThat(rows("salida_cost", tenant)).isEqualTo(1);
  }

  @Test
  void I_the_application_user_cannot_change_a_line_once_it_is_recorded() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      costs().add(expense(tenant, departure, 10_000L), "cost-key-0001");
      return null;
    });
    assertThatThrownBy(() -> jdbc.sql("UPDATE salida_cost SET amount_cents = 1 WHERE tenant_id = :tenant").param("tenant", tenant).update())
        .rootCause().hasMessageContaining("permission denied");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM salida_cost WHERE tenant_id = :tenant").param("tenant", tenant).update())
        .rootCause().hasMessageContaining("permission denied");
  }
}
