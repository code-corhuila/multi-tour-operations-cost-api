package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureExecution;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** The execution of a departure over the real engine: what is stored, the conditional update and the page (api 004 S4-04). */
class PostgresDepartureExecutionTest extends PostgresTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final UUID STARTER = UUID.fromString("00000000-0000-4000-8000-0000000000dd");
  private static final UUID FINISHER = UUID.fromString("00000000-0000-4000-8000-0000000000ee");

  /** A departure that already began (the domain refuses to schedule one in the past, the engine does not). */
  private static Departure began(UUID tenant, Instant start) {
    Departure departure = new Departure(UUID.randomUUID(), tenant, departures.nextCode(tenant), TOUR, start, start.plus(Duration.ofHours(8)),
        "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, start.minus(Duration.ofDays(2)));
    departures.save(departure);
    return departure;
  }

  @Test
  void I_a_departure_that_never_ran_is_read_with_an_empty_execution() {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(3)));
    Departure read = departures.find(tenant, created.id()).orElseThrow();
    assertThat(read).isEqualTo(created);
    assertThat(read.execution()).isEqualTo(DepartureExecution.NONE);
  }

  @Test
  void I_a_start_and_an_end_are_stored_with_who_recorded_them() {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(3)));
    Departure started = created.start(NOW.minus(Duration.ofHours(2)), STARTER, NOW);
    assertThat(departures.updateExecution(created, started)).isTrue();
    Departure readStarted = departures.find(tenant, created.id()).orElseThrow();
    assertThat(readStarted).isEqualTo(started);
    assertThat(readStarted.execution().startedBy()).isEqualTo(STARTER);

    Departure finished = readStarted.finish(NOW.minus(Duration.ofMinutes(5)), FINISHER, NOW, "Heavy rain closed the trail");
    assertThat(departures.updateExecution(readStarted, finished)).isTrue();
    Departure readFinished = departures.find(tenant, created.id()).orElseThrow();
    assertThat(readFinished).isEqualTo(finished);
    assertThat(readFinished.status()).isEqualTo(DepartureStatus.FINISHED);
    assertThat(readFinished.execution().interrupted()).isTrue();
    assertThat(readFinished.execution().interruptionCause()).isEqualTo("Heavy rain closed the trail");
  }

  @Test
  void I_a_change_decided_on_a_stale_reading_writes_nothing() {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(3)));
    Departure one = created.start(NOW.minus(Duration.ofHours(2)), STARTER, NOW);
    Departure other = created.start(NOW.minus(Duration.ofHours(1)), FINISHER, NOW);
    assertThat(departures.updateExecution(created, one)).isTrue();
    assertThat(departures.updateExecution(created, other)).isFalse();
    assertThat(departures.find(tenant, created.id()).orElseThrow()).isEqualTo(one);
  }

  @Test
  void I_a_departure_of_another_tenant_is_not_touched() {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(3)));
    Departure strangers = new Departure(created.id(), UUID.randomUUID(), created.code(), TOUR, created.start(), created.estimatedEnd(),
        "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, created.scheduledAt());
    assertThat(departures.updateExecution(strangers, strangers.start(NOW.minus(Duration.ofHours(2)), STARTER, NOW))).isFalse();
    assertThat(departures.find(tenant, created.id()).orElseThrow().status()).isEqualTo(DepartureStatus.SCHEDULED);
  }

  @Test
  void I_two_starts_at_once_only_one_wins() throws Exception {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(3)));
    Departure a = created.start(NOW.minus(Duration.ofHours(2)), STARTER, NOW);
    Departure b = created.start(NOW.minus(Duration.ofHours(1)), FINISHER, NOW);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      Future<Boolean> first = pool.submit(() -> {
        go.await();
        return departures.updateExecution(created, a);
      });
      Future<Boolean> second = pool.submit(() -> {
        go.await();
        return departures.updateExecution(created, b);
      });
      go.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void I_the_page_lists_the_tenant_most_recent_first_and_filters_by_status() {
    UUID tenant = UUID.randomUUID();
    Departure oldest = began(tenant, NOW.minus(Duration.ofDays(5)));
    Departure middle = began(tenant, NOW.minus(Duration.ofDays(3)));
    Departure newest = began(tenant, NOW.minus(Duration.ofDays(1)));
    began(UUID.randomUUID(), NOW.minus(Duration.ofDays(2)));
    Departure started = middle.start(middle.start().plusSeconds(60), STARTER, NOW);
    assertThat(departures.updateExecution(middle, started)).isTrue();

    Page<Departure> all = departures.page(tenant, Optional.empty(), 1, 2);
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.data()).extracting(Departure::code).containsExactly(newest.code(), middle.code());
    assertThat(departures.page(tenant, Optional.empty(), 2, 2).data()).extracting(Departure::code).containsExactly(oldest.code());
    assertThat(departures.page(tenant, Optional.empty(), 3, 2).data()).isEmpty();

    Page<Departure> running = departures.page(tenant, Optional.of(DepartureStatus.IN_EXECUTION), 1, 20);
    assertThat(running.total()).isEqualTo(1);
    assertThat(running.data()).containsExactly(started);
    assertThat(departures.page(tenant, Optional.of(DepartureStatus.CANCELLED), 1, 20).total()).isZero();
  }
}
