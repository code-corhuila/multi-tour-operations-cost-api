package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.BusinessRuleViolationException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidStatusTransitionException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Outbox;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.RescheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Reservations;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ScheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Changing the date over the real engine: the conditional update, the event written with it and the tenant filter. */
class PostgresRescheduleDepartureTest extends PostgresTest {

  private static final Instant START = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS);
  private static final Instant END = START.plus(Duration.ofHours(8));

  private static Caller admin(UUID tenant) {
    return new Caller(ACCOUNT.toString(), tenant.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
  }

  private static Departure scheduled(UUID tenant) {
    return useCase(outbox).execute(new ScheduleDeparture.Command(admin(tenant), "key-" + UUID.randomUUID(), TOUR, START, END,
        "America/Bogota", "trace-it")).departure();
  }

  private static RescheduleDeparture reschedule(Outbox events, Optional<Reservations.Seats> seats) {
    return new RescheduleDeparture(departures, events, (service, departure) -> seats,
        serviceId -> Optional.empty(), vehicles, transaction, UUID::randomUUID, Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000)));
  }

  private static RescheduleDeparture.Command command(UUID tenant, String code, Instant start, Instant end) {
    return new RescheduleDeparture.Command(admin(tenant), code, start, end, "trace-it-2");
  }

  @Test
  void I_the_dates_and_the_event_are_written_together_and_the_code_stays() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant);
    Instant newStart = START.plus(Duration.ofDays(1));
    RescheduleDeparture.Rescheduled result =
        reschedule(outbox, Optional.of(new Reservations.Seats(12, 0))).execute(command(tenant, created.code(), newStart, newStart.plus(Duration.ofHours(6))));

    assertThat(result.changed()).isTrue();
    Departure stored = departures.find(tenant, created.id()).orElseThrow();
    assertThat(stored.start()).isEqualTo(newStart);
    assertThat(stored.estimatedEnd()).isEqualTo(newStart.plus(Duration.ofHours(6)));
    assertThat(stored.code()).isEqualTo(created.code());
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE tenant_id = :t AND event_type = 'SalidaRescheduled' AND status = 'PENDING'")
        .param("t", tenant).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void I_a_failed_event_leaves_the_dates_as_they_were() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant);
    Outbox failing = new Outbox() {
      @Override
      public void add(co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent event) {
        throw new IllegalStateException("the outbox is down");
      }

      @Override
      public boolean waiting(UUID tenantId, UUID aggregateId, String eventType) {
        return false;
      }
    };
    assertThatThrownBy(() -> reschedule(failing, Optional.of(new Reservations.Seats(12, 0)))
        .execute(command(tenant, created.code(), START.plus(Duration.ofDays(1)), END.plus(Duration.ofDays(1)))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(departures.find(tenant, created.id()).orElseThrow().start()).isEqualTo(START);
  }

  @Test
  void I_a_departure_of_another_tenant_is_not_found_and_is_not_touched() {
    UUID tenant = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Departure created = scheduled(tenant);
    assertThat(departures.findByCode(stranger, created.code())).isEmpty();
    assertThat(departures.findByCode(tenant, created.code())).contains(created);
    Departure strangers = new Departure(created.id(), stranger, created.code(), TOUR, START, END, "America/Bogota", created.status(),
        ACCOUNT, created.scheduledAt());
    assertThat(departures.updateSchedule(strangers, strangers.reschedule(START.plusSeconds(60), END, Instant.now()))).isFalse();
    assertThat(departures.find(tenant, created.id()).orElseThrow().start()).isEqualTo(START);
  }

  @Test
  void I_the_update_is_refused_once_the_departure_is_no_longer_scheduled() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant);
    jdbc.sql("UPDATE salida SET status = 'CANCELLED' WHERE salida_id = :id").param("id", created.id()).update();
    // a change decided on a stale reading (it was still SCHEDULED) writes nothing
    Departure stale = created.reschedule(START.plusSeconds(3600), END.plusSeconds(3600), Instant.now());
    assertThat(departures.updateSchedule(created, stale)).isFalse();
    assertThat(departures.find(tenant, created.id()).orElseThrow().start()).isEqualTo(START);
    // and a fresh reading is refused by the domain before the engine is asked
    assertThatThrownBy(() -> reschedule(outbox, Optional.of(new Reservations.Seats(12, 0)))
        .execute(command(tenant, created.code(), START.plusSeconds(60), END)))
        .isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void I_two_changes_decided_on_the_same_reading_write_only_the_first() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant);
    Departure one = created.reschedule(START.plusSeconds(3600), END.plusSeconds(3600), Instant.now());
    Departure other = created.reschedule(START.plusSeconds(7200), END.plusSeconds(7200), Instant.now());
    assertThat(departures.updateSchedule(created, one)).isTrue();
    assertThat(departures.updateSchedule(created, other)).as("the dates are no longer the ones that were read").isFalse();
    assertThat(departures.find(tenant, created.id()).orElseThrow().start()).isEqualTo(START.plusSeconds(3600));
  }

  @Test
  void I_a_departure_with_seats_held_is_not_changed() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant);
    assertThatThrownBy(() -> reschedule(outbox, Optional.of(new Reservations.Seats(12, 3)))
        .execute(command(tenant, created.code(), START.plus(Duration.ofDays(1)), END.plus(Duration.ofDays(1)))))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(departures.find(tenant, created.id()).orElseThrow().start()).isEqualTo(START);
  }

  @Test
  void I_with_no_row_in_reservations_the_departure_is_free_while_its_event_waits_and_not_after() {
    UUID tenant = UUID.randomUUID();
    Departure created = scheduled(tenant);
    assertThat(outbox.waiting(tenant, created.id(), "SalidaScheduled")).isTrue();
    assertThat(outbox.waiting(tenant, created.id(), "SalidaRescheduled")).isFalse();
    assertThat(outbox.waiting(UUID.randomUUID(), created.id(), "SalidaScheduled")).as("another tenant").isFalse();
    assertThat(outbox.waiting(tenant, UUID.randomUUID(), "SalidaScheduled")).isFalse();
    assertThat(reschedule(outbox, Optional.empty())
        .execute(command(tenant, created.code(), START.plus(Duration.ofDays(1)), END.plus(Duration.ofDays(1)))).changed()).isTrue();

    // the event was delivered: the relay marks it published, and the same case can no longer be proved empty
    jdbc.sql("UPDATE outbox_event SET status = 'PUBLISHED', published_at = now() WHERE aggregate_id = :id AND event_type = 'SalidaScheduled'")
        .param("id", created.id()).update();
    assertThat(outbox.waiting(tenant, created.id(), "SalidaScheduled")).isFalse();
    assertThatThrownBy(() -> reschedule(outbox, Optional.empty())
        .execute(command(tenant, created.code(), START.plus(Duration.ofDays(2)), END.plus(Duration.ofDays(2)))))
        .isInstanceOf(BusinessRuleViolationException.class);
  }
}
