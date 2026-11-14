package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Permission;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-005 (api spec 009), AC-API-05.1 to -05.4: the extraordinary cancellation of a reservation of a running departure, ports as fakes. */
class RecordExtraordinaryCancellationTest {

  private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
  private static final Instant START = NOW.minus(Duration.ofHours(4));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final UUID R1 = new UUID(0, 0x101);
  private static final UUID R2 = new UUID(0, 0x102);
  private static final UUID R3 = new UUID(0, 0x103);
  private static final String KEY = "cancel-key-0001";
  private static final String TRACE = "trace-77";
  private static final String WHY = "the traveler had a medical emergency";

  private FakeWorld world;
  private RecordExtraordinaryCancellation record;
  private Departure running;
  private final Caller person =
      new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, false));
    record = new RecordExtraordinaryCancellation(world, world, world, world, new ServicesReader(world, world, world), new DepartureViews(world, world, world), world,
        world, Clock.fixed(NOW, ZoneOffset.UTC));
    running = departure(1, DepartureStatus.SCHEDULED).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(running.id(), running);
    world.reservationRows.put(running.id(), List.of(
        new ReservationOfDeparture(R1, "Ana Perez", 4, ReservationState.IN_EXECUTION),
        new ReservationOfDeparture(R2, "Luis Gomez", 2, ReservationState.IN_EXECUTION),
        new ReservationOfDeparture(R3, "Eva Rios", 3, ReservationState.CANCELLED)));
  }

  private Departure departure(int n, DepartureStatus status) {
    Departure created = new Departure(new UUID(0, 1000 + n), TENANT, String.format("SAL-%03d", n), TOUR, START, START.plus(Duration.ofHours(8)),
        "America/Bogota", status, ACCOUNT, START.minus(Duration.ofDays(2)));
    world.departures.put(created.id(), created);
    return created;
  }

  private RecordExtraordinaryCancellation.Command command(String code, UUID reservation, String key, String justification) {
    return new RecordExtraordinaryCancellation.Command(person, code, reservation.toString(), key, justification, TRACE);
  }

  @Test
  void A_the_cancellation_is_recorded_with_who_and_when() {
    RecordExtraordinaryCancellation.Recorded result = record.execute(command("SAL-001", R1, KEY, WHY));
    assertThat(result.recorded()).isTrue();
    assertThat(world.cancellationRows.get(running.id())).containsOnlyKeys(R1);
    ExtraordinaryCancellation stored = world.cancellationRows.get(running.id()).get(R1);
    assertThat(stored.justification()).isEqualTo(WHY);
    assertThat(stored.recordedBy()).isEqualTo(ACCOUNT);
    assertThat(stored.recordedAt()).isEqualTo(NOW);
    assertThat(world.keys).hasSize(1);
  }

  @Test
  void A_the_event_carries_tenant_departure_reservation_justification_and_who() {
    record.execute(command("SAL-001", R1, KEY, WHY));
    assertThat(world.events).hasSize(1);
    OutboxEvent event = world.events.get(0);
    assertThat(event.eventType()).isEqualTo("ExtraordinaryCancellationRecorded");
    assertThat(event.aggregateId()).isEqualTo(running.id());
    assertThat(event.tenantId()).isEqualTo(TENANT);
    assertThat(event.actorId()).isEqualTo(ACCOUNT);
    assertThat(event.correlationId()).isEqualTo(TRACE);
    assertThat(event.payload()).containsEntry("tenantId", TENANT.toString()).containsEntry("salidaId", running.id().toString())
        .containsEntry("reservationId", R1.toString()).containsEntry("justification", WHY).containsEntry("recordedBy", ACCOUNT.toString());
  }

  @Test
  void A_the_answer_shows_the_cancellation_next_to_its_reservation() {
    RecordExtraordinaryCancellation.Recorded result = record.execute(command("SAL-001", R1, KEY, WHY));
    var reservations = result.services().orElseThrow().reservations();
    assertThat(reservations.get(0).cancellation()).contains(new ExtraordinaryCancellation(R1, WHY, ACCOUNT, NOW));
    assertThat(reservations.get(1).cancellation()).isEmpty();
  }

  @Test
  void A_the_reservation_is_never_written() {
    record.execute(command("SAL-001", R1, KEY, WHY));
    assertThat(world.reservationRows.get(running.id())).hasSize(3);
    assertThat(world.reservationRows.get(running.id()).get(0).state()).isEqualTo(ReservationState.IN_EXECUTION);
  }

  @Test
  void A_reservation_of_another_departure_is_refused() {
    assertThatThrownBy(() -> record.execute(command("SAL-001", new UUID(0, 0x999), KEY, WHY))).isInstanceOf(BusinessRuleViolationException.class);
    assertNothingWritten();
  }

  @Test
  void A_reservation_not_in_execution_is_refused() {
    assertThatThrownBy(() -> record.execute(command("SAL-001", R3, KEY, WHY))).isInstanceOf(BusinessRuleViolationException.class);
    assertNothingWritten();
  }

  @Test
  void A_second_cancellation_of_a_reservation_is_refused() {
    record.execute(command("SAL-001", R1, KEY, WHY));
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, "cancel-key-0002", "another reason"))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.events).hasSize(1);
    assertThat(world.cancellationRows.get(running.id()).get(R1).justification()).isEqualTo(WHY);
  }

  @Test
  void A_the_same_key_answers_the_same_departure_and_writes_once() {
    record.execute(command("SAL-001", R1, KEY, WHY));
    RecordExtraordinaryCancellation.Recorded again = record.execute(command("SAL-001", R1, KEY, WHY));
    assertThat(again.recorded()).isFalse();
    assertThat(world.events).hasSize(1);
    assertThat(world.keys).hasSize(1);
  }

  @Test
  void A_a_key_used_for_another_departure_is_refused() {
    Departure other = departure(2, DepartureStatus.SCHEDULED).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(other.id(), other);
    record.execute(command("SAL-001", R1, KEY, WHY));
    assertThatThrownBy(() -> record.execute(command("SAL-002", R1, KEY, WHY))).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void A_a_departure_that_is_not_in_execution_is_refused_before_asking_anyone() {
    Departure finished = running.finish(NOW, ACCOUNT, NOW, null);
    world.departures.put(finished.id(), finished);
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, WHY))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.reservationCalls).isZero();
    assertNothingWritten();
  }

  @Test
  void A_reservations_down_writes_nothing() {
    world.reservationsDown = true;
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, WHY))).isInstanceOf(DependencyUnavailableException.class);
    assertNothingWritten();
  }

  @Test
  void A_an_empty_justification_is_refused_before_asking_anyone() {
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, " "))).isInstanceOf(InvalidDataException.class);
    assertThat(world.reservationCalls).isZero();
    assertNothingWritten();
  }

  @Test
  void A_a_down_outbox_rolls_the_cancellation_and_the_key_back() {
    world.outboxFails = true;
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, WHY))).isInstanceOf(IllegalStateException.class);
    assertNothingWritten();
  }

  @Test
  void A_a_finish_that_wins_the_lock_refuses_the_cancellation_and_writes_nothing() {
    world.onLock = () -> world.departures.put(running.id(), running.finish(NOW, ACCOUNT, NOW, null));
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, WHY))).isInstanceOf(InvalidStatusTransitionException.class);
    assertNothingWritten();
  }

  @Test
  void A_a_request_that_loses_a_same_key_race_answers_the_one_recorded() {
    world.onLock = () -> record.execute(command("SAL-001", R1, KEY, WHY));
    RecordExtraordinaryCancellation.Recorded result = record.execute(command("SAL-001", R1, KEY, WHY));
    assertThat(result.recorded()).isFalse();
    assertThat(world.events).hasSize(1);
    assertThat(world.cancellationRows.get(running.id())).containsOnlyKeys(R1);
  }

  @Test
  void A_the_departure_of_another_tenant_is_not_found() {
    Caller stranger = new Caller(ACCOUNT.toString(), new UUID(0, 0xab).toString(), Set.of(Role.TENANT_ADMINISTRATOR),
        Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));
    assertThatThrownBy(() -> record.execute(new RecordExtraordinaryCancellation.Command(stranger, "SAL-001", R1.toString(), KEY, WHY, TRACE)))
        .isInstanceOf(DepartureNotFoundException.class);
  }

  private void assertNothingWritten() {
    assertThat(world.cancellationRows).isEmpty();
    assertThat(world.events).isEmpty();
    assertThat(world.keys).isEmpty();
  }
}
