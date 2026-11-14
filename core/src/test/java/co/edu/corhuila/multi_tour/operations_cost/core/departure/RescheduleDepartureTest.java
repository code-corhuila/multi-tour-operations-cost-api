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

/** The use case with the ports as fakes (RN-OPS-API-11, -14, -16, -17; AC3 and AC4 of the story). */
class RescheduleDepartureTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.plus(Duration.ofDays(10));
  private static final Instant END = START.plus(Duration.ofHours(8));
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final String TRACE = "trace-42";

  private FakeWorld world;
  private RescheduleDeparture reschedule;
  private final Caller admin = new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 12));
    world.departures.put(ID, new Departure(ID, TENANT, "SAL-001", TOUR, START, END, "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, NOW));
    world.seats.put(ID, new Reservations.Seats(12, 0));
    reschedule = new RescheduleDeparture(world, world, world, world, world, world, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private RescheduleDeparture.Command command(String code, Instant start, Instant end) {
    return new RescheduleDeparture.Command(admin, code, start, end, TRACE);
  }

  private final Instant newStart = START.plus(Duration.ofDays(1));
  private final Instant newEnd = END.plus(Duration.ofDays(1));

  @Test
  void A_the_dates_change_and_the_code_and_the_state_stay() {
    RescheduleDeparture.Rescheduled result = reschedule.execute(command("SAL-001", newStart, newEnd));
    assertThat(result.changed()).isTrue();
    assertThat(result.capacity()).isEqualTo(12);
    assertThat(result.departure().start()).isEqualTo(newStart);
    assertThat(result.departure().estimatedEnd()).isEqualTo(newEnd);
    assertThat(result.departure().code()).isEqualTo("SAL-001");
    assertThat(result.departure().status()).isEqualTo(DepartureStatus.SCHEDULED);
    assertThat(world.departures.get(ID).start()).isEqualTo(newStart);
  }

  @Test
  void A_the_event_waits_with_the_new_dates_the_actor_and_the_correlation_id() {
    reschedule.execute(command("SAL-001", newStart, newEnd));
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("SalidaRescheduled");
      assertThat(event.aggregateId()).isEqualTo(ID);
      assertThat(event.tenantId()).isEqualTo(TENANT);
      assertThat(event.actorId()).isEqualTo(ACCOUNT);
      assertThat(event.correlationId()).isEqualTo(TRACE);
      assertThat(event.occurredAt()).isEqualTo(NOW);
      assertThat(event.payload())
          .containsEntry("tenantId", TENANT.toString())
          .containsEntry("salidaId", ID.toString())
          .containsEntry("serviceId", TOUR.toString())
          .containsEntry("startsAt", newStart.toString())
          .containsEntry("estimatedEndsAt", newEnd.toString());
    });
  }

  @Test
  void A_the_same_values_are_no_change_and_ask_nobody_anything() {
    world.seats.put(ID, new Reservations.Seats(12, 5));
    world.reservationsDown = true;
    RescheduleDeparture.Rescheduled result = reschedule.execute(command("SAL-001", START, END));
    assertThat(result.changed()).isFalse();
    assertThat(world.events).isEmpty();
    assertThat(world.reservationCalls).isZero();
  }

  @Test
  void A_a_departure_that_is_not_there_or_is_another_tenants_is_not_found() {
    assertThatThrownBy(() -> reschedule.execute(command("SAL-099", newStart, newEnd))).isInstanceOf(DepartureNotFoundException.class);
    UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
    Caller stranger = new Caller(ACCOUNT.toString(), other.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
    assertThatThrownBy(() -> reschedule.execute(new RescheduleDeparture.Command(stranger, "SAL-001", newStart, newEnd, TRACE)))
        .isInstanceOf(DepartureNotFoundException.class);
    assertThat(world.departures.get(ID).start()).isEqualTo(START);
  }

  @Test
  void A_a_departure_that_is_not_scheduled_is_refused_as_a_transition() {
    for (DepartureStatus status : new DepartureStatus[] {DepartureStatus.IN_EXECUTION, DepartureStatus.FINISHED, DepartureStatus.CANCELLED}) {
      world.departures.put(ID, new Departure(ID, TENANT, "SAL-001", TOUR, START, END, "America/Bogota", status, ACCOUNT, NOW));
      assertThatThrownBy(() -> reschedule.execute(command("SAL-001", newStart, newEnd))).as(status.name())
          .isInstanceOf(InvalidStatusTransitionException.class);
    }
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_departure_that_is_not_scheduled_refuses_even_the_same_values() {
    world.departures.put(ID, new Departure(ID, TENANT, "SAL-001", TOUR, START, END, "America/Bogota", DepartureStatus.CANCELLED, ACCOUNT, NOW));
    assertThatThrownBy(() -> reschedule.execute(command("SAL-001", START, END))).isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void A_the_same_values_of_a_scheduled_departure_whose_start_has_passed_are_no_change() {
    Instant past = NOW.minus(Duration.ofDays(1));
    world.departures.put(ID, new Departure(ID, TENANT, "SAL-001", TOUR, past, past.plusSeconds(3600), "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, NOW));
    assertThat(reschedule.execute(command("SAL-001", past, past.plusSeconds(3600))).changed()).isFalse();
    assertThat(world.reservationCalls).isZero();
  }

  @Test
  void A_the_event_of_another_tenant_does_not_free_the_departure() {
    world.seats.clear();
    UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
    world.events.add(new OutboxEvent(new UUID(5, 5), "SalidaScheduled", ID, other, ACCOUNT, TRACE, java.util.Map.of(), NOW));
    assertThatThrownBy(() -> reschedule.execute(command("SAL-001", newStart, newEnd))).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void A_a_departure_with_reservations_is_never_changed() {
    world.seats.put(ID, new Reservations.Seats(12, 2));
    assertThatThrownBy(() -> reschedule.execute(command("SAL-001", newStart, newEnd))).isInstanceOf(BusinessRuleViolationException.class)
        .hasMessageContaining("reservations");
    assertThat(world.departures.get(ID).start()).isEqualTo(START);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_with_no_row_in_reservations_it_is_allowed_only_while_the_scheduled_event_still_waits() {
    world.seats.clear();
    world.events.add(new OutboxEvent(new UUID(5, 5), "SalidaScheduled", ID, TENANT, ACCOUNT, TRACE, java.util.Map.of(), NOW));
    assertThat(reschedule.execute(command("SAL-001", newStart, newEnd)).changed()).isTrue();

    world.departures.put(ID, new Departure(ID, TENANT, "SAL-001", TOUR, START, END, "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, NOW));
    world.events.clear();
    assertThatThrownBy(() -> reschedule.execute(command("SAL-001", newStart, newEnd))).isInstanceOf(BusinessRuleViolationException.class)
        .hasMessageContaining("cannot be confirmed");
    assertThat(world.departures.get(ID).start()).isEqualTo(START);
  }

  @Test
  void A_reservations_that_cannot_be_read_change_nothing() {
    world.reservationsDown = true;
    assertThatThrownBy(() -> reschedule.execute(command("SAL-001", newStart, newEnd))).isInstanceOf(DependencyUnavailableException.class);
    assertThat(world.departures.get(ID).start()).isEqualTo(START);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_bad_dates_are_judged_before_reservations_are_asked() {
    assertThatThrownBy(() -> reschedule.execute(command("SAL-001", newStart, newStart.minusSeconds(1)))).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> reschedule.execute(command("SAL-001", NOW.minusSeconds(1), NOW.plusSeconds(60))))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> reschedule.execute(command("SAL-001", null, newEnd))).isInstanceOf(InvalidDataException.class);
    assertThat(world.reservationCalls).isZero();
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_departure_that_stopped_being_scheduled_meanwhile_is_not_changed_and_leaves_no_event() {
    world.updateRefused = true;
    assertThatThrownBy(() -> reschedule.execute(command("SAL-001", newStart, newEnd))).isInstanceOf(BusinessRuleViolationException.class)
        .hasMessageContaining("changed");
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_a_failed_event_leaves_the_dates_as_they_were() {
    world.outboxFails = true;
    assertThatThrownBy(() -> reschedule.execute(command("SAL-001", newStart, newEnd))).isInstanceOf(IllegalStateException.class);
    assertThat(world.departures.get(ID).start()).isEqualTo(START);
    assertThat(world.events).isEmpty();
  }

  @Test
  void A_the_capacity_is_the_one_of_the_tour_and_zero_when_the_catalog_does_not_say() {
    world.catalogDown = true;
    assertThat(reschedule.execute(command("SAL-001", newStart, newEnd)).capacity()).isZero();
  }

  @Test
  void A_a_caller_that_is_not_a_person_of_a_tenant_and_a_bad_correlation_id_are_refused() {
    Caller noUuid = new Caller("account-1", TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
    assertThatThrownBy(() -> reschedule.execute(new RescheduleDeparture.Command(noUuid, "SAL-001", newStart, newEnd, TRACE)))
        .isInstanceOf(CallerNotAllowedException.class);
    for (String bad : new String[] {null, "", " ", "t".repeat(129)}) {
      assertThatThrownBy(() -> reschedule.execute(new RescheduleDeparture.Command(admin, "SAL-001", newStart, newEnd, bad)))
          .isInstanceOf(InvalidDataException.class);
    }
    assertThat(world.events).isEmpty();
  }
}
