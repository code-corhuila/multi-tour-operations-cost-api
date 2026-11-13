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

/** The use case with the ports as fakes: no server, no database (RN-OPS-API-12, -14, -15, -17). */
class ScheduleDepartureTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.plus(Duration.ofDays(10));
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final String KEY = "key-0001-abcdef";

  private FakeWorld world;
  private ScheduleDeparture schedule;
  private final Caller admin = new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 12));
    schedule = new ScheduleDeparture(world, world, world, world, world, world, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private ScheduleDeparture.Command command(String key, UUID tour, Instant start, Instant end) {
    return new ScheduleDeparture.Command(admin, key, tour, start, end, "America/Bogota");
  }

  private ScheduleDeparture.Command command() {
    return command(KEY, TOUR, START, START.plus(Duration.ofHours(8)));
  }

  @Test
  void A_a_departure_is_scheduled_with_the_next_code_and_the_capacity_of_the_tour() {
    ScheduleDeparture.Scheduled result = schedule.execute(command());
    assertThat(result.created()).isTrue();
    assertThat(result.capacity()).isEqualTo(12);
    Departure departure = result.departure();
    assertThat(departure.status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(departure.code()).isEqualTo("SAL-001");
    assertThat(departure.tenantId()).isEqualTo(TENANT);
    assertThat(departure.scheduledBy()).isEqualTo(ACCOUNT);
    assertThat(departure.scheduledAt()).isEqualTo(NOW);
    assertThat(world.departures).containsValue(departure);
  }

  @Test
  void A_the_second_departure_of_a_tenant_takes_the_next_code() {
    schedule.execute(command());
    assertThat(schedule.execute(command("key-0002-abcdef", TOUR, START, START)).departure().code()).isEqualTo("SAL-002");
  }

  @Test
  void A_the_event_waits_in_the_outbox_with_what_a_consumer_needs() {
    Departure departure = schedule.execute(command()).departure();
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaScheduled");
      assertThat(event.aggregateId()).isEqualTo(departure.id());
      assertThat(event.tenantId()).isEqualTo(TENANT);
      assertThat(event.occurredAt()).isEqualTo(NOW);
      assertThat(event.payload())
          .containsEntry("tenantId", TENANT.toString())
          .containsEntry("salidaId", departure.id().toString())
          .containsEntry("serviceId", TOUR.toString())
          .containsEntry("startsAt", START.toString())
          .containsEntry("estimatedEndsAt", START.plus(Duration.ofHours(8)).toString())
          .doesNotContainKey("scheduledBy");
    });
  }

  @Test
  void A_unknown_tour_is_refused_and_nothing_is_written() {
    UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000ee");
    assertThatThrownBy(() -> schedule.execute(command(KEY, other, START, START)))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertNothingWritten();
  }

  @Test
  void A_transport_item_is_refused() {
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TRANSPORTATION, true, 12));
    assertThatThrownBy(() -> schedule.execute(command())).isInstanceOf(BusinessRuleViolationException.class);
    assertNothingWritten();
  }

  @Test
  void A_inactive_tour_is_refused() {
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, false, 12));
    assertThatThrownBy(() -> schedule.execute(command())).isInstanceOf(BusinessRuleViolationException.class);
    assertNothingWritten();
  }

  @Test
  void A_the_same_key_answers_the_departure_already_created_and_creates_nothing() {
    Departure first = schedule.execute(command()).departure();
    ScheduleDeparture.Scheduled repeat = schedule.execute(command(KEY, TOUR, START.plusSeconds(60), START.plusSeconds(120)));
    assertThat(repeat.created()).isFalse();
    assertThat(repeat.departure()).isEqualTo(first);
    assertThat(repeat.capacity()).isEqualTo(12);
    assertThat(world.departures).hasSize(1);
    assertThat(world.events).hasSize(1);
  }

  @Test
  void A_a_key_is_only_good_for_its_own_tenant() {
    schedule.execute(command());
    UUID otherTenant = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
    Caller other = new Caller(ACCOUNT.toString(), otherTenant.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
    ScheduleDeparture.Scheduled result = schedule.execute(
        new ScheduleDeparture.Command(other, KEY, TOUR, START, START, "America/Bogota"));
    assertThat(result.created()).isTrue();
    assertThat(world.departures).hasSize(2);
  }

  @Test
  void A_a_key_shorter_than_8_or_longer_than_128_characters_is_refused() {
    for (String bad : new String[] {null, "", "short-7", "k".repeat(129)}) {
      assertThatThrownBy(() -> schedule.execute(command(bad, TOUR, START, START)))
          .as(String.valueOf(bad))
          .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("Idempotency-Key"));
    }
    assertThat(schedule.execute(command("k".repeat(128), TOUR, START, START)).created()).isTrue();
    assertThat(world.catalogCalls).as("only the valid request read the catalog").isEqualTo(1);
  }

  @Test
  void A_invalid_dates_write_nothing() {
    assertThatThrownBy(() -> schedule.execute(command(KEY, TOUR, START, START.minusSeconds(1))))
        .isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> schedule.execute(command(KEY, TOUR, NOW.minusSeconds(1), NOW.plusSeconds(60))))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertNothingWritten();
  }

  @Test
  void A_never_writes_when_a_read_fails() {
    world.catalogDown = true;
    assertThatThrownBy(() -> schedule.execute(command())).isInstanceOf(DependencyUnavailableException.class);
    assertNothingWritten();
  }

  @Test
  void A_a_failed_event_leaves_no_departure_and_no_key() {
    world.outboxFails = true;
    assertThatThrownBy(() -> schedule.execute(command())).isInstanceOf(IllegalStateException.class);
    assertNothingWritten();
  }

  @Test
  void A_a_caller_whose_subject_or_tenant_is_not_a_uuid_is_not_allowed() {
    Caller noUuid = new Caller("account-1", TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
    assertThatThrownBy(() -> schedule.execute(new ScheduleDeparture.Command(noUuid, KEY, TOUR, START, START, "America/Bogota")))
        .isInstanceOf(CallerNotAllowedException.class);
    Caller noTenantUuid = new Caller(ACCOUNT.toString(), "tenant-1", Set.of(Role.TENANT_ADMINISTRATOR));
    assertThatThrownBy(() -> schedule.execute(new ScheduleDeparture.Command(noTenantUuid, KEY, TOUR, START, START, "America/Bogota")))
        .isInstanceOf(CallerNotAllowedException.class);
    assertNothingWritten();
  }

  @Test
  void A_a_caller_without_a_tenant_is_not_allowed() {
    Caller platform = new Caller(ACCOUNT.toString(), null, Set.of(Role.PLATFORM_ADMINISTRATOR));
    assertThatThrownBy(() -> schedule.execute(new ScheduleDeparture.Command(platform, KEY, TOUR, START, START, "America/Bogota")))
        .isInstanceOf(CallerNotAllowedException.class);
  }

  private void assertNothingWritten() {
    assertThat(world.departures).isEmpty();
    assertThat(world.events).isEmpty();
    assertThat(world.keys).isEmpty();
  }

  @Test
  void A_a_repeat_answers_even_when_the_catalog_is_down_with_a_capacity_of_zero() {
    schedule.execute(command());
    world.catalogDown = true;
    ScheduleDeparture.Scheduled repeat = schedule.execute(command());
    assertThat(repeat.created()).isFalse();
    assertThat(repeat.capacity()).isZero();
  }

  @Test
  void A_the_request_that_loses_the_race_for_a_key_answers_the_winners_departure() {
    UUID winner = new UUID(1, 1);
    world.departures.put(winner, Departure.schedule(winner, TENANT, "SAL-001", TOUR, START, START, "America/Bogota", ACCOUNT, NOW));
    world.racedDeparture = winner;
    ScheduleDeparture.Scheduled result = schedule.execute(command());
    assertThat(result.created()).isFalse();
    assertThat(result.departure().id()).isEqualTo(winner);
    assertThat(world.departures).hasSize(1);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_key_that_points_to_a_missing_departure_is_a_failure_and_not_a_new_departure() {
    world.keys.put(TENANT + "/" + KEY, new UUID(9, 9));
    assertThatThrownBy(() -> schedule.execute(command())).isInstanceOf(IllegalStateException.class);
    assertThat(world.departures).isEmpty();
  }
}
