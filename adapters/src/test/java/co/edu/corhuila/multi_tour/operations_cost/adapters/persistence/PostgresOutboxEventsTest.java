package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.RetryPolicy;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.StoredEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The outbox as job 7 reads it, over the real engine: what waits, the delivery mark and the retry (ADR-004 D4, D10). */
class PostgresOutboxEventsTest extends PostgresTest {

  private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 20);

  private static JdbcOutboxEvents events() {
    return new JdbcOutboxEvents(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
  }

  /** An event written the way the use cases write it, for a tenant of its own so the tests do not meet. */
  private static UUID write(UUID tenant, Instant occurredAt) {
    UUID id = UUID.randomUUID();
    outbox.add(new OutboxEvent(id, "SalidaScheduled", UUID.randomUUID(), tenant, ACCOUNT, "trace-" + id, Map.of("tenantId", tenant.toString()), occurredAt));
    return id;
  }

  /** The events due of one tenant: the listing is across tenants, so every page is read and this tenant's are kept. */
  private static Page<StoredEvent> mine(UUID tenant, Instant now, int page, int limit) {
    List<StoredEvent> ofTenant = new java.util.ArrayList<>();
    for (int number = 1; ; number++) {
      Page<StoredEvent> all = events().due(now, number, 100);
      all.data().stream().filter(e -> e.tenantId().equals(tenant)).forEach(ofTenant::add);
      if (number >= all.totalPages()) {
        break;
      }
    }
    return new Page<>(ofTenant, page, limit, ofTenant.size());
  }

  @Test
  void I_a_written_event_is_listed_with_everything_job_7_needs() {
    UUID tenant = UUID.randomUUID();
    Instant at = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
    UUID id = write(tenant, at);
    StoredEvent event = mine(tenant, Instant.now(), 1, 100).data().get(0);
    assertThat(event.eventId()).isEqualTo(id);
    assertThat(event.eventType()).isEqualTo("SalidaScheduled");
    assertThat(event.eventVersion()).isEqualTo(1);
    assertThat(event.tenantId()).isEqualTo(tenant);
    assertThat(event.actorId()).isEqualTo(ACCOUNT);
    assertThat(event.payload()).containsEntry("tenantId", tenant.toString());
    assertThat(event.status()).isEqualTo(StoredEvent.Status.PENDING);
    assertThat(event.attempts()).isZero();
    assertThat(event.occurredAt()).isEqualTo(at);
    assertThat(event.publishedAt()).isNull();
  }

  @Test
  void I_the_listing_is_oldest_first_and_paged() {
    UUID tenant = UUID.randomUUID();
    Instant base = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);
    UUID third = write(tenant, base.plusSeconds(3));
    UUID first = write(tenant, base.plusSeconds(1));
    UUID second = write(tenant, base.plusSeconds(2));
    List<UUID> order = mine(tenant, Instant.now(), 1, 100).data().stream().map(StoredEvent::eventId).toList();
    assertThat(order).containsExactly(first, second, third);
    Page<StoredEvent> page = events().due(Instant.now(), 1, 1);
    assertThat(page.data()).hasSize(1);
    assertThat(page.total()).isGreaterThanOrEqualTo(3);
    assertThat(page.totalPages()).isGreaterThanOrEqualTo(3);
    assertThat(events().due(Instant.now(), 2, 1).data()).hasSize(1);
  }

  @Test
  void I_a_published_event_leaves_the_listing_and_publishing_twice_changes_nothing() {
    UUID tenant = UUID.randomUUID();
    UUID id = write(tenant, Instant.now().minusSeconds(30));
    Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
    StoredEvent done = events().markPublished(id, at).orElseThrow();
    assertThat(done.status()).isEqualTo(StoredEvent.Status.PUBLISHED);
    assertThat(done.publishedAt()).isEqualTo(at);
    assertThat(mine(tenant, Instant.now(), 1, 100).data()).isEmpty();
    StoredEvent again = events().markPublished(id, at.plusSeconds(60)).orElseThrow();
    assertThat(again.publishedAt()).as("the first mark stays").isEqualTo(at);
    assertThat(events().markPublished(UUID.randomUUID(), at)).isEmpty();
  }

  @Test
  void I_a_failed_attempt_counts_keeps_the_error_and_moves_the_next_attempt() {
    UUID tenant = UUID.randomUUID();
    UUID id = write(tenant, Instant.now().minusSeconds(30));
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    StoredEvent one = events().recordFailedAttempt(id, "consumer answered 503", now, POLICY).orElseThrow();
    assertThat(one.attempts()).isEqualTo(1);
    assertThat(one.lastError()).isEqualTo("consumer answered 503");
    assertThat(one.nextAttemptAt()).isEqualTo(now.plusSeconds(5));
    assertThat(one.status()).isEqualTo(StoredEvent.Status.PENDING);
    assertThat(mine(tenant, now, 1, 100).data()).as("it waits for its next attempt").isEmpty();
    assertThat(mine(tenant, now.plusSeconds(6), 1, 100).data()).hasSize(1);
    StoredEvent two = events().recordFailedAttempt(id, "again", now, POLICY).orElseThrow();
    assertThat(two.attempts()).isEqualTo(2);
    assertThat(two.nextAttemptAt()).isEqualTo(now.plusSeconds(10));
  }

  @Test
  void I_the_last_attempt_gives_the_event_up_and_a_late_report_touches_nothing() {
    UUID tenant = UUID.randomUUID();
    UUID id = write(tenant, Instant.now().minusSeconds(30));
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    RetryPolicy three = new RetryPolicy(Duration.ofSeconds(5), Duration.ofSeconds(8), 3);
    events().recordFailedAttempt(id, "1", now, three);
    events().recordFailedAttempt(id, "2", now, three);
    StoredEvent last = events().recordFailedAttempt(id, "3", now, three).orElseThrow();
    assertThat(last.status()).isEqualTo(StoredEvent.Status.FAILED);
    assertThat(last.attempts()).isEqualTo(3);
    assertThat(Duration.between(now, last.nextAttemptAt())).isEqualTo(Duration.ofSeconds(8));
    assertThat(events().recordFailedAttempt(id, "late", now, three).orElseThrow().attempts()).isEqualTo(3);
    assertThat(events().markPublished(id, now).orElseThrow().status()).as("a delivery that did succeed is true").isEqualTo(StoredEvent.Status.PUBLISHED);
  }

  @Test
  void I_reports_of_failure_at_once_are_all_counted() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID id = write(tenant, Instant.now().minusSeconds(30));
    int reports = 5;
    ExecutorService pool = Executors.newFixedThreadPool(reports);
    try {
      java.util.concurrent.CountDownLatch together = new java.util.concurrent.CountDownLatch(reports);
      List<Callable<Optional<StoredEvent>>> work = new java.util.ArrayList<>();
      for (int i = 0; i < reports; i++) {
        work.add(() -> {
          together.countDown();
          together.await();
          return events().recordFailedAttempt(id, "boom", Instant.now(), POLICY);
        });
      }
      for (Future<Optional<StoredEvent>> done : pool.invokeAll(work)) {
        done.get();
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(events().markPublished(id, Instant.now()).orElseThrow().attempts()).isEqualTo(reports);
  }
}
