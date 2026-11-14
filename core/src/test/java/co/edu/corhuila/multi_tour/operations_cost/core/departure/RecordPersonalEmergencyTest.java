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

/** HU-OPS-008 (api spec 011), AC-API-11.1 to -11.4 and -11.6: the personal emergency of a reservation of a running departure, ports as fakes. */
class RecordPersonalEmergencyTest {

  private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
  private static final Instant START = NOW.minus(Duration.ofHours(4));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final UUID R1 = new UUID(0, 0x101);
  private static final UUID R2 = new UUID(0, 0x102);
  private static final UUID R3 = new UUID(0, 0x103);
  private static final String KEY = "emergency-key-0001";
  private static final String TRACE = "trace-88";
  private static final String PERSONAL_EMERGENCY = "PERSONAL_EMERGENCY";
  private static final String DETAIL = "a traveler fainted on the trail and went down with the guide";

  private FakeWorld world;
  private RecordPersonalEmergency record;
  private Departure running;
  private final Caller person =
      new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, false));
    record = new RecordPersonalEmergency(world, world, world, world, world, new DepartureViews(world, world, world), world, world,
        Clock.fixed(NOW, ZoneOffset.UTC));
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

  private RecordPersonalEmergency.Command command(String code, UUID reservation, String key, int affected, String detail) {
    return new RecordPersonalEmergency.Command(person, code, reservation.toString(), PERSONAL_EMERGENCY, key, detail, affected, TRACE);
  }

  @Test
  void A_the_emergency_is_recorded_with_who_when_and_how_many() {
    RecordPersonalEmergency.Recorded result = record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    assertThat(result.recorded()).isTrue();
    assertThat(world.emergencyRows.get(running.id())).containsOnlyKeys(R1);
    PersonalEmergency stored = world.emergencyRows.get(running.id()).get(R1);
    assertThat(stored.affected()).isEqualTo(2);
    assertThat(stored.detail()).isEqualTo(DETAIL);
    assertThat(stored.recordedBy()).isEqualTo(ACCOUNT);
    assertThat(stored.recordedAt()).isEqualTo(NOW);
    assertThat(world.keys).hasSize(1);
    assertThat(result.view().departure().status()).isEqualTo(DepartureStatus.IN_EXECUTION);
  }

  @Test
  void A_the_event_carries_tenant_departure_reservation_affected_and_who() {
    record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    assertThat(world.events).hasSize(1);
    OutboxEvent event = world.events.get(0);
    assertThat(event.eventType()).isEqualTo("PersonalEmergencyRecorded");
    assertThat(event.aggregateId()).isEqualTo(running.id());
    assertThat(event.tenantId()).isEqualTo(TENANT);
    assertThat(event.actorId()).isEqualTo(ACCOUNT);
    assertThat(event.correlationId()).isEqualTo(TRACE);
    assertThat(event.payload()).containsEntry("tenantId", TENANT.toString()).containsEntry("salidaId", running.id().toString())
        .containsEntry("reservationId", R1.toString()).containsEntry("affected", 2).containsEntry("recordedBy", ACCOUNT.toString())
        .containsKey("recordedAt");
  }

  @Test
  void A_the_event_never_carries_the_detail() {
    record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    assertThat(world.events.get(0).payload()).doesNotContainValue(DETAIL).doesNotContainKey("detail");
    assertThat(world.events.get(0).payload().toString()).doesNotContain("fainted");
  }

  @Test
  void A_the_reservation_is_never_written() {
    record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    assertThat(world.reservationRows.get(running.id())).hasSize(3);
    assertThat(world.reservationRows.get(running.id()).get(0).state()).isEqualTo(ReservationState.IN_EXECUTION);
  }

  @Test
  void A_affected_above_the_travelers_of_the_reservation_is_refused() {
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, 5, DETAIL))).isInstanceOf(BusinessRuleViolationException.class);
    assertNothingWritten();
    assertThat(record.execute(command("SAL-001", R1, KEY, 4, DETAIL)).recorded()).isTrue();
  }

  @Test
  void A_a_reservation_of_another_departure_or_not_in_execution_is_refused() {
    assertThatThrownBy(() -> record.execute(command("SAL-001", new UUID(0, 0x999), KEY, 1, DETAIL))).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> record.execute(command("SAL-001", R3, KEY, 1, DETAIL))).isInstanceOf(BusinessRuleViolationException.class);
    assertNothingWritten();
  }

  @Test
  void A_a_second_emergency_of_a_reservation_is_refused() {
    record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, "emergency-key-0002", 1, "another one"))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.events).hasSize(1);
    assertThat(world.emergencyRows.get(running.id()).get(R1).detail()).isEqualTo(DETAIL);
  }

  @Test
  void A_the_same_key_answers_the_same_departure_and_writes_once() {
    record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    RecordPersonalEmergency.Recorded again = record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    assertThat(again.recorded()).isFalse();
    assertThat(world.events).hasSize(1);
    assertThat(world.keys).hasSize(1);
  }

  @Test
  void A_a_key_used_for_another_departure_is_refused() {
    Departure other = departure(2, DepartureStatus.SCHEDULED).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(other.id(), other);
    record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    assertThatThrownBy(() -> record.execute(command("SAL-002", R1, KEY, 2, DETAIL))).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void A_a_departure_that_is_not_in_execution_is_refused_before_asking_anyone() {
    Departure finished = running.finish(NOW, ACCOUNT, NOW, null);
    world.departures.put(finished.id(), finished);
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, 2, DETAIL))).isInstanceOf(InvalidStatusTransitionException.class);
    assertThat(world.reservationCalls).isZero();
    assertNothingWritten();
  }

  @Test
  void A_the_ordinary_novelty_is_not_offered() {
    assertThatThrownBy(() -> record.execute(new RecordPersonalEmergency.Command(person, "SAL-001", R1.toString(), "NOVELTY", KEY, DETAIL, 2, TRACE)))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> record.execute(new RecordPersonalEmergency.Command(person, "SAL-001", R1.toString(), "OTHER", KEY, DETAIL, 2, TRACE)))
        .isInstanceOf(InvalidDataException.class);
    assertThat(world.reservationCalls).isZero();
    assertNothingWritten();
  }

  @Test
  void A_the_request_is_judged_before_the_departure_or_another_domain_is_looked_at() {
    for (RecordPersonalEmergency.Command bad : new RecordPersonalEmergency.Command[] {
        command("SAL-999", R1, KEY, 0, DETAIL), command("SAL-999", R1, KEY, 2, " "), command("SAL-999", R1, KEY, 2, "d".repeat(1001)),
        new RecordPersonalEmergency.Command(person, "SAL-999", R1.toString(), PERSONAL_EMERGENCY, KEY, DETAIL, null, TRACE),
        new RecordPersonalEmergency.Command(person, "SAL-999", "not-a-uuid", PERSONAL_EMERGENCY, KEY, DETAIL, 2, TRACE)}) {
      assertThatThrownBy(() -> record.execute(bad)).isInstanceOf(InvalidDataException.class);
    }
    assertThat(world.reservationCalls).isZero();
    assertNothingWritten();
  }

  @Test
  void A_reservations_down_writes_nothing() {
    world.reservationsDown = true;
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, 2, DETAIL))).isInstanceOf(DependencyUnavailableException.class);
    assertNothingWritten();
  }

  @Test
  void A_a_down_outbox_rolls_the_emergency_and_the_key_back() {
    world.outboxFails = true;
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, 2, DETAIL))).isInstanceOf(IllegalStateException.class);
    assertNothingWritten();
  }

  @Test
  void A_a_finish_that_wins_the_lock_refuses_the_emergency_and_writes_nothing() {
    world.onLock = () -> world.departures.put(running.id(), running.finish(NOW, ACCOUNT, NOW, null));
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, 2, DETAIL))).isInstanceOf(InvalidStatusTransitionException.class);
    assertNothingWritten();
  }

  @Test
  void A_a_request_that_loses_a_same_key_race_answers_the_one_recorded() {
    world.onLock = () -> record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    RecordPersonalEmergency.Recorded result = record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    assertThat(result.recorded()).isFalse();
    assertThat(world.events).hasSize(1);
    assertThat(world.emergencyRows.get(running.id())).containsOnlyKeys(R1);
  }

  @Test
  void A_a_request_with_another_key_that_loses_the_race_for_the_reservation_is_refused() {
    world.onLock = () -> record.execute(command("SAL-001", R1, "emergency-key-0002", 1, "the winner"));
    assertThatThrownBy(() -> record.execute(command("SAL-001", R1, KEY, 2, DETAIL))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.events).hasSize(1);
    assertThat(world.emergencyRows.get(running.id()).get(R1).detail()).isEqualTo("the winner");
  }

  @Test
  void A_a_missing_type_is_refused() {
    assertThatThrownBy(() -> record.execute(new RecordPersonalEmergency.Command(person, "SAL-001", R1.toString(), null, KEY, DETAIL, 2, TRACE)))
        .isInstanceOf(InvalidDataException.class);
    assertNothingWritten();
  }

  @Test
  void A_the_same_key_still_answers_after_the_departure_finished() {
    record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    Departure finished = world.departures.get(running.id()).finish(NOW, ACCOUNT, NOW, null);
    world.departures.put(finished.id(), finished);
    RecordPersonalEmergency.Recorded again = record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    assertThat(again.recorded()).isFalse();
    assertThat(world.events).hasSize(1);
  }

  @Test
  void A_the_emergencies_of_a_departure_are_read_back_through_the_port() {
    record.execute(command("SAL-001", R1, KEY, 2, DETAIL));
    assertThat(world.emergenciesOf(TENANT, running.id())).extracting(PersonalEmergency::reservationId).containsExactly(R1);
  }

  @Test
  void A_the_departure_of_another_tenant_is_not_found() {
    Caller stranger = new Caller(ACCOUNT.toString(), new UUID(0, 0xab).toString(), Set.of(Role.TENANT_ADMINISTRATOR),
        Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));
    assertThatThrownBy(() -> record.execute(
        new RecordPersonalEmergency.Command(stranger, "SAL-001", R1.toString(), PERSONAL_EMERGENCY, KEY, DETAIL, 2, TRACE)))
        .isInstanceOf(DepartureNotFoundException.class);
  }

  private void assertNothingWritten() {
    assertThat(world.emergencyRows).isEmpty();
    assertThat(world.events).isEmpty();
    assertThat(world.keys).isEmpty();
  }
}
