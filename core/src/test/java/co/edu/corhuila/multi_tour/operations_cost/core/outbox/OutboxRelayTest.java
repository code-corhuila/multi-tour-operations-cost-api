package co.edu.corhuila.multi_tour.operations_cost.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What job 7 asks of the outbox (ADR-004 D4, D10): read what waits, mark it delivered, report a failed delivery. */
class OutboxRelayTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 20);

  /** The store in memory, with the rules of the real one written the simple way. */
  private static final class Store implements OutboxEvents {
    final List<StoredEvent> events = new ArrayList<>();

    @Override
    public Page<StoredEvent> due(Instant now, int page, int limit) {
      List<StoredEvent> due = events.stream().filter(e -> e.status() == StoredEvent.Status.PENDING && !e.nextAttemptAt().isAfter(now))
          .sorted(java.util.Comparator.comparing(StoredEvent::occurredAt)).toList();
      int from = Math.min((page - 1) * limit, due.size());
      return new Page<>(due.subList(from, Math.min(from + limit, due.size())), page, limit, due.size());
    }

    @Override
    public Optional<StoredEvent> markPublished(UUID eventId, Instant at) {
      return replace(eventId, e -> e.published(at));
    }

    @Override
    public Optional<StoredEvent> recordFailedAttempt(UUID eventId, String error, Instant at, RetryPolicy policy) {
      return replace(eventId, e -> e.withFailedAttempt(error, at, policy));
    }

    private Optional<StoredEvent> replace(UUID id, java.util.function.UnaryOperator<StoredEvent> change) {
      for (int i = 0; i < events.size(); i++) {
        if (events.get(i).eventId().equals(id)) {
          events.set(i, change.apply(events.get(i)));
          return Optional.of(events.get(i));
        }
      }
      return Optional.empty();
    }
  }

  private Store store;
  private OutboxRelay relay;

  private static StoredEvent pending(int n, Instant occurredAt) {
    return new StoredEvent(new UUID(0, n), "SalidaScheduled", 1, new UUID(1, 1), new UUID(2, n), new UUID(3, 3), null,
        Map.of("salidaId", new UUID(2, n).toString()), "trace-" + n, occurredAt, null, StoredEvent.Status.PENDING, 0, null, occurredAt);
  }

  @BeforeEach
  void setUp() {
    store = new Store();
    relay = new OutboxRelay(store, Clock.fixed(NOW, ZoneOffset.UTC), POLICY);
  }

  @Test
  void A_it_lists_what_waits_oldest_first_in_pages() {
    for (int i = 1; i <= 5; i++) {
      store.events.add(pending(i, NOW.minusSeconds(100 - i)));
    }
    Page<StoredEvent> first = relay.listDue(1, 2);
    assertThat(first.data()).extracting(StoredEvent::eventId).containsExactly(new UUID(0, 1), new UUID(0, 2));
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.totalPages()).isEqualTo(3);
    assertThat(relay.listDue(3, 2).data()).extracting(StoredEvent::eventId).containsExactly(new UUID(0, 5));
  }

  @Test
  void A_an_event_that_waits_for_its_next_attempt_is_not_listed() {
    store.events.add(pending(1, NOW.minusSeconds(10)));
    store.events.add(new StoredEvent(new UUID(0, 2), "SalidaScheduled", 1, new UUID(1, 1), new UUID(2, 2), null, null, Map.of(), "t",
        NOW.minusSeconds(20), null, StoredEvent.Status.PENDING, 3, "boom", NOW.plusSeconds(60)));
    assertThat(relay.listDue(1, 100).data()).extracting(StoredEvent::eventId).containsExactly(new UUID(0, 1));
  }

  @Test
  void A_a_page_below_one_or_a_limit_outside_1_to_100_is_refused_and_not_trimmed() {
    assertThatThrownBy(() -> relay.listDue(0, 10)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> relay.listDue(1, 0)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> relay.listDue(1, 101)).isInstanceOf(InvalidDataException.class);
    assertThat(relay.listDue(1, 100).data()).isEmpty();
  }

  @Test
  void A_a_delivered_event_is_marked_published_with_the_instant() {
    store.events.add(pending(1, NOW.minusSeconds(10)));
    StoredEvent done = relay.markPublished(new UUID(0, 1));
    assertThat(done.status()).isEqualTo(StoredEvent.Status.PUBLISHED);
    assertThat(done.publishedAt()).isEqualTo(NOW);
    assertThat(relay.listDue(1, 100).data()).isEmpty();
  }

  @Test
  void A_publishing_twice_changes_nothing_and_an_event_given_up_is_published_too() {
    store.events.add(pending(1, NOW.minusSeconds(10)));
    StoredEvent first = relay.markPublished(new UUID(0, 1));
    assertThat(relay.markPublished(new UUID(0, 1))).isEqualTo(first);
    store.events.add(new StoredEvent(new UUID(0, 2), "SalidaScheduled", 1, new UUID(1, 1), new UUID(2, 2), null, null, Map.of(), "t",
        NOW.minusSeconds(20), null, StoredEvent.Status.FAILED, 20, "gave up", NOW));
    assertThat(relay.markPublished(new UUID(0, 2)).status()).isEqualTo(StoredEvent.Status.PUBLISHED);
  }

  @Test
  void A_an_event_that_does_not_exist_is_not_found() {
    assertThatThrownBy(() -> relay.markPublished(new UUID(9, 9))).isInstanceOf(OutboxEventNotFoundException.class);
    assertThatThrownBy(() -> relay.recordFailedAttempt(new UUID(9, 9), "boom")).isInstanceOf(OutboxEventNotFoundException.class);
  }

  @Test
  void A_a_failed_attempt_counts_keeps_the_error_and_waits_longer_each_time() {
    store.events.add(pending(1, NOW.minusSeconds(10)));
    StoredEvent one = relay.recordFailedAttempt(new UUID(0, 1), "consumer answered 503");
    assertThat(one.attempts()).isEqualTo(1);
    assertThat(one.lastError()).isEqualTo("consumer answered 503");
    assertThat(one.status()).isEqualTo(StoredEvent.Status.PENDING);
    assertThat(one.nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
    assertThat(relay.recordFailedAttempt(new UUID(0, 1), "again").nextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
    assertThat(relay.recordFailedAttempt(new UUID(0, 1), "again").nextAttemptAt()).isEqualTo(NOW.plusSeconds(20));
  }

  @Test
  void A_the_wait_never_goes_above_the_cap_and_the_last_attempt_gives_the_event_up() {
    store.events.add(pending(1, NOW.minusSeconds(10)));
    StoredEvent last = null;
    for (int i = 1; i <= 20; i++) {
      last = relay.recordFailedAttempt(new UUID(0, 1), "boom " + i);
      assertThat(Duration.between(NOW, last.nextAttemptAt())).isLessThanOrEqualTo(Duration.ofMinutes(15));
    }
    assertThat(last.attempts()).isEqualTo(20);
    assertThat(last.status()).isEqualTo(StoredEvent.Status.FAILED);
    assertThat(relay.recordFailedAttempt(new UUID(0, 1), "late report").attempts()).as("not touched once given up").isEqualTo(20);
  }

  @Test
  void A_an_event_no_longer_pending_is_not_touched_by_a_report_that_came_late() {
    store.events.add(pending(1, NOW.minusSeconds(10)));
    relay.markPublished(new UUID(0, 1));
    StoredEvent unchanged = relay.recordFailedAttempt(new UUID(0, 1), "late");
    assertThat(unchanged.status()).isEqualTo(StoredEvent.Status.PUBLISHED);
    assertThat(unchanged.attempts()).isZero();
  }

  @Test
  void A_the_error_is_required_short_and_plain() {
    store.events.add(pending(1, NOW.minusSeconds(10)));
    for (String bad : new String[] {null, "", "  ", "x".repeat(1001), "line\nbreak", "bidi‮override"}) {
      assertThatThrownBy(() -> relay.recordFailedAttempt(new UUID(0, 1), bad)).as(String.valueOf(bad)).isInstanceOf(InvalidDataException.class);
    }
    assertThat(relay.recordFailedAttempt(new UUID(0, 1), "x".repeat(1000)).attempts()).isEqualTo(1);
  }

  @Test
  void A_the_retry_policy_needs_a_wait_a_cap_and_an_attempt() {
    assertThatThrownBy(() -> new RetryPolicy(Duration.ZERO, Duration.ofMinutes(1), 5)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new RetryPolicy(Duration.ofMinutes(2), Duration.ofMinutes(1), 5)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), 0)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), 1001)).isInstanceOf(InvalidDataException.class);
    assertThat(POLICY.waitAfter(1)).isEqualTo(Duration.ofSeconds(5));
    assertThat(POLICY.waitAfter(4)).isEqualTo(Duration.ofSeconds(40));
    assertThat(POLICY.waitAfter(30)).isEqualTo(Duration.ofMinutes(15));
  }
}
