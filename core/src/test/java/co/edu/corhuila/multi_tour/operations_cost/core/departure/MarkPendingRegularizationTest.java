package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-003 part B (api spec 005), AC-API-05.2 to -05.5: job 3 marks the departures past their estimated end, ports as fakes. */
class MarkPendingRegularizationTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID OTHER_TENANT = new UUID(0, 0xab);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final String TRACE = "trace-job-3";

  private FakeWorld world;
  private MarkPendingRegularization mark;
  private final Caller service = new Caller("worker-departure-deadlines", null, Set.of(Role.SERVICE));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    mark = new MarkPendingRegularization(world, world, world, world, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** A departure whose estimated end was {@code endedAgo} before now. */
  private Departure put(int n, DepartureStatus status, UUID tenant, Duration endedAgo) {
    Instant end = NOW.minus(endedAgo);
    Departure created = new Departure(new UUID(0, 1000 + n), tenant, String.format("SAL-%03d", n), TOUR, end.minus(Duration.ofHours(8)), end,
        "America/Bogota", status, ACCOUNT, end.minus(Duration.ofDays(3)));
    world.departures.put(created.id(), created);
    return created;
  }

  private int ask(int limit) {
    return mark.execute(new MarkPendingRegularization.Command(service, limit, TRACE));
  }

  @Test
  void A_the_departures_of_every_tenant_past_their_end_are_marked_with_one_event_each_and_no_state_change() {
    Departure mine = put(1, DepartureStatus.SCHEDULED, TENANT, Duration.ofHours(1));
    Departure running = put(2, DepartureStatus.IN_EXECUTION, OTHER_TENANT, Duration.ofMinutes(5));
    assertThat(ask(100)).isEqualTo(2);
    assertThat(world.departures.get(mine.id()).status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(world.departures.get(running.id()).status()).isEqualTo(DepartureStatus.IN_EXECUTION);
    assertThat(world.departures.get(mine.id()).execution().pendingRegularization()).isTrue();
    assertThat(world.departures.get(mine.id()).execution().pendingMarkedAt()).isEqualTo(NOW);
    assertThat(world.events).hasSize(2).allSatisfy(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaMarkedPendienteDeRegularizacion");
      assertThat(event.actorId()).isNull();
      assertThat(event.correlationId()).isEqualTo(TRACE);
      assertThat(event.payload()).containsKeys("tenantId", "salidaId", "code", "estimatedEnd", "markedAt");
      assertThat(event.payload()).containsEntry("markedAt", NOW.toString());
    });
    assertThat(world.events).extracting(OutboxEvent::tenantId).containsExactlyInAnyOrder(TENANT, OTHER_TENANT);
  }

  @Test
  void A_the_marked_the_closed_and_the_ones_not_yet_past_their_end_are_skipped() {
    put(1, DepartureStatus.SCHEDULED, TENANT, Duration.ofHours(1));
    put(2, DepartureStatus.FINISHED, TENANT, Duration.ofHours(1));
    put(3, DepartureStatus.CANCELLED, TENANT, Duration.ofHours(1));
    put(4, DepartureStatus.SCHEDULED, TENANT, Duration.ofHours(-1));
    assertThat(ask(100)).isEqualTo(1);
    assertThat(ask(100)).isZero();
    assertThat(world.events).hasSize(1);
  }

  @Test
  void A_each_ask_is_bounded_by_the_limit_and_takes_the_oldest_end_first() {
    Departure oldest = put(1, DepartureStatus.SCHEDULED, TENANT, Duration.ofDays(3));
    put(2, DepartureStatus.SCHEDULED, TENANT, Duration.ofDays(1));
    put(3, DepartureStatus.SCHEDULED, TENANT, Duration.ofHours(1));
    assertThat(ask(1)).isEqualTo(1);
    assertThat(world.departures.get(oldest.id()).execution().pendingRegularization()).isTrue();
    assertThat(ask(1)).isEqualTo(1);
    assertThat(ask(5)).isEqualTo(1);
    assertThat(ask(5)).isZero();
  }

  @Test
  void A_nothing_to_mark_is_a_quiet_ask() {
    assertThat(ask(100)).isZero();
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_failure_writing_the_event_marks_nothing() {
    Departure open = put(1, DepartureStatus.SCHEDULED, TENANT, Duration.ofHours(1));
    world.outboxFails = true;
    assertThatThrownBy(() -> ask(100)).isInstanceOf(RuntimeException.class);
    assertThat(world.departures.get(open.id()).execution().pendingRegularization()).isFalse();
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_limit_out_of_range_is_refused_by_field() {
    for (int bad : new int[] {0, -1, 501}) {
      assertThatThrownBy(() -> ask(bad)).isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("limit"));
    }
    assertThat(ask(500)).isZero();
  }

  @Test
  void A_only_a_service_may_ask() {
    Caller person = new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
    assertThatThrownBy(() -> mark.execute(new MarkPendingRegularization.Command(person, 100, TRACE))).isInstanceOf(CallerNotAllowedException.class);
  }
}
