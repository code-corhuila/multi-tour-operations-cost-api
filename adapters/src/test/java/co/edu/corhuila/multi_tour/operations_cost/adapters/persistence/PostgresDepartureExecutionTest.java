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
  void I_a_mark_is_stored_and_read_and_a_start_clears_it_but_keeps_its_moment() {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(12)));
    Departure marked = created.markPending(NOW);
    assertThat(departures.updateExecution(created, marked)).isTrue();
    Departure read = departures.find(tenant, created.id()).orElseThrow();
    assertThat(read).isEqualTo(marked);
    assertThat(read.status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(read.execution().pendingRegularization()).isTrue();
    assertThat(read.execution().pendingMarkedAt()).isEqualTo(NOW);
    Departure started = read.start(NOW.minus(Duration.ofHours(11)), STARTER, NOW);
    assertThat(departures.updateExecution(read, started)).isTrue();
    Departure afterStart = departures.find(tenant, created.id()).orElseThrow();
    assertThat(afterStart.execution().pendingRegularization()).isFalse();
    assertThat(afterStart.execution().pendingMarkedAt()).isEqualTo(NOW);
  }

  @Test
  void I_a_rescheduled_marked_departure_is_stored_as_no_longer_pending() {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(12)));
    Departure marked = created.markPending(NOW);
    assertThat(departures.updateExecution(created, marked)).isTrue();
    Departure moved = marked.reschedule(NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(2)).plus(Duration.ofHours(6)), NOW);
    assertThat(departures.updateSchedule(marked, moved)).isTrue();
    Departure read = departures.find(tenant, created.id()).orElseThrow();
    assertThat(read.execution().pendingRegularization()).isFalse();
    assertThat(read.execution().pendingMarkedAt()).isEqualTo(NOW);
  }

  @Test
  void I_the_open_departures_past_their_end_are_found_oldest_first_across_tenants_and_the_marked_are_skipped() {
    UUID one = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    Departure older = began(one, NOW.minus(Duration.ofDays(5)));
    Departure newer = began(other, NOW.minus(Duration.ofDays(4)));
    Departure future = began(one, NOW.plus(Duration.ofDays(1)));
    Departure closed = began(one, NOW.minus(Duration.ofDays(6)));
    Departure closedRunning = closed.start(NOW.minus(Duration.ofDays(6)), STARTER, NOW);
    assertThat(departures.updateExecution(closed, closedRunning)).isTrue();
    assertThat(departures.updateExecution(closedRunning, closedRunning.finish(NOW.minus(Duration.ofDays(5)), STARTER, NOW, null))).isTrue();
    List<UUID> found = transaction.run(() -> departures.lockOpenPastEnd(NOW, 500).stream().map(Departure::id).toList());
    assertThat(found).contains(older.id(), newer.id()).doesNotContain(future.id(), closed.id());
    assertThat(found.indexOf(older.id())).isLessThan(found.indexOf(newer.id()));
    assertThat(transaction.run(() -> departures.lockOpenPastEnd(NOW, 1))).hasSize(1);
    assertThat(departures.updateExecution(older, older.markPending(NOW))).isTrue();
    assertThat(transaction.run(() -> departures.lockOpenPastEnd(NOW, 500).stream().map(Departure::id).toList()))
        .contains(newer.id()).doesNotContain(older.id());
  }

  @Test
  void I_two_overlapping_asks_never_take_the_same_departure() throws Exception {
    UUID tenant = UUID.randomUUID();
    Departure a = began(tenant, NOW.minus(Duration.ofDays(8)));
    Departure b = began(tenant, NOW.minus(Duration.ofDays(7)));
    CountDownLatch firstHolds = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<List<UUID>> first = pool.submit(() -> transaction.run(() -> {
        List<UUID> mine = departures.lockOpenPastEnd(NOW, 500).stream().filter(d -> d.tenantId().equals(tenant)).map(Departure::id).toList();
        firstHolds.countDown();
        try {
          secondDone.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        return mine;
      }));
      Future<List<UUID>> second = pool.submit(() -> {
        firstHolds.await();
        try {
          return transaction.run(() -> departures.lockOpenPastEnd(NOW, 500).stream().filter(d -> d.tenantId().equals(tenant)).map(Departure::id).toList());
        } finally {
          secondDone.countDown();
        }
      });
      assertThat(first.get()).containsExactlyInAnyOrder(a.id(), b.id());
      assertThat(second.get()).isEmpty();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void I_a_normal_end_round_trips_without_an_interruption() {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(3)));
    Departure started = created.start(NOW.minus(Duration.ofHours(2)), STARTER, NOW);
    assertThat(departures.updateExecution(created, started)).isTrue();
    Departure finished = started.finish(NOW.minus(Duration.ofMinutes(5)), FINISHER, NOW, null);
    assertThat(departures.updateExecution(started, finished)).isTrue();
    Departure read = departures.find(tenant, created.id()).orElseThrow();
    assertThat(read).isEqualTo(finished);
    assertThat(read.execution().interrupted()).isFalse();
    assertThat(read.execution().interruptionCause()).isNull();
  }

  @Test
  void I_two_ends_at_once_only_one_wins() throws Exception {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(3)));
    Departure started = created.start(NOW.minus(Duration.ofHours(2)), STARTER, NOW);
    assertThat(departures.updateExecution(created, started)).isTrue();
    Departure a = started.finish(NOW.minus(Duration.ofMinutes(10)), STARTER, NOW, null);
    Departure b = started.finish(NOW.minus(Duration.ofMinutes(5)), FINISHER, NOW, "Heavy rain");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      Future<Boolean> first = pool.submit(() -> {
        go.await();
        return departures.updateExecution(started, a);
      });
      Future<Boolean> second = pool.submit(() -> {
        go.await();
        return departures.updateExecution(started, b);
      });
      go.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void I_the_same_key_is_another_key_for_another_operation_and_for_another_tenant() {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(3)));
    keys.register(tenant, "registerStart", "key-0000001", created.id());
    assertThat(keys.departureOf(tenant, "registerStart", "key-0000001")).contains(created.id());
    assertThat(keys.departureOf(tenant, "finishDeparture", "key-0000001")).isEmpty();
    assertThat(keys.departureOf(tenant, "key-0000001")).isEmpty();
    assertThat(keys.departureOf(UUID.randomUUID(), "registerStart", "key-0000001")).isEmpty();
    keys.register(tenant, "finishDeparture", "key-0000001", created.id());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> keys.register(tenant, "registerStart", "key-0000001", created.id()))
        .isInstanceOf(co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException.class);
  }

  @Test
  void I_a_departure_read_back_is_the_one_that_was_stored_for_every_state_the_engine_accepts() {
    UUID tenant = UUID.randomUUID();
    Departure created = began(tenant, NOW.minus(Duration.ofHours(3)));
    Departure started = created.start(NOW.minus(Duration.ofHours(2)), STARTER, NOW);
    assertThat(departures.updateExecution(created, started)).isTrue();
    assertThat(departures.findByCode(tenant, created.code())).contains(started);
    assertThat(departures.findForUpdate(tenant, created.id())).contains(started);
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
    assertThat(departures.page(tenant, Optional.of(DepartureStatus.SCHEDULED), 1, 20).data()).extracting(Departure::code)
        .containsExactly(newest.code(), oldest.code());
  }
}
