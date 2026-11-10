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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-007 (api spec 007), AC-API-07.1 to -07.8, -07.11 and -07.12: recording and reading the costs of a departure, ports as fakes. */
class RecordCostTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final Instant START = NOW.minus(Duration.ofHours(4));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID OTHER_TENANT = new UUID(0, 0xab);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final UUID R1 = new UUID(0, 0x101);
  private static final String KEY = "cost-key-00001";
  private static final String TRACE = "trace-42";

  private FakeWorld world;
  private RecordCost record;
  private ListCosts list;
  private Departure running;
  private final Caller person =
      new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18));
    record = new RecordCost(world, world, world, world, world, world, world, Clock.fixed(NOW, ZoneOffset.UTC));
    list = new ListCosts(world, world, world, world);
    running = departure(1, DepartureStatus.SCHEDULED, TENANT).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(running.id(), running);
  }

  private Departure departure(int n, DepartureStatus status, UUID tenant) {
    Departure created = new Departure(new UUID(0, 1000 + n), tenant, String.format("SAL-%03d", n), TOUR, START, START.plus(Duration.ofHours(8)),
        "America/Bogota", status, ACCOUNT, START.minus(Duration.ofDays(2)));
    world.departures.put(created.id(), created);
    return created;
  }

  private void catalogued(long cents) {
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, false, cents));
  }

  private void served(UUID departure, int travelers) {
    world.serviceRows.computeIfAbsent(departure, id -> new java.util.LinkedHashMap<>())
        .put(R1, new ServiceRecord(R1, ServiceResult.DELIVERED, travelers, null, false));
  }

  private RecordCost.Command expense(String code, String key, Long amount, String justification) {
    return new RecordCost.Command(person, code, key, "Fuel of the boat", CostMode.MANUAL, amount, justification, TRACE);
  }

  private RecordCost.Command calculation(String code, String key) {
    return new RecordCost.Command(person, code, key, "Boat and guide", CostMode.CALCULATED, null, null, TRACE);
  }

  @Test
  void A_a_real_expense_is_recorded_with_its_event_and_changes_no_reservation() {
    catalogued(21_000_000L);
    served(running.id(), 4);
    RecordCost.Recorded result = record.execute(expense("SAL-001", KEY, 50_000L, null));
    assertThat(result.recorded()).isTrue();
    assertThat(world.costRows).singleElement().satisfies(cost -> {
      assertThat(cost.amountCents()).isEqualTo(50_000L);
      assertThat(cost.mode()).isEqualTo(CostMode.MANUAL);
      assertThat(cost.recordedBy()).isEqualTo(ACCOUNT);
      assertThat(cost.departureId()).isEqualTo(running.id());
    });
    assertThat(world.events).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo("OperationalCostRecorded");
      assertThat(event.tenantId()).isEqualTo(TENANT);
      assertThat(event.actorId()).isEqualTo(ACCOUNT);
      assertThat(event.payload()).containsEntry("amountCents", 50_000L).containsEntry("mode", "MANUAL").containsEntry("withoutParametrization", false)
          .containsEntry("salidaId", running.id().toString());
    });
    assertThat(world.serviceRows.get(running.id())).hasSize(1);
    assertThat(world.departures.get(running.id())).isEqualTo(running);
  }

  @Test
  void A_a_calculated_cost_is_the_catalogued_cost_times_the_travelers_served() {
    catalogued(21_000_000L);
    served(running.id(), 4);
    RecordCost.Recorded result = record.execute(calculation("SAL-001", KEY));
    assertThat(result.cost().amountCents()).isEqualTo(84_000_000L);
    assertThat(result.cost().unitCostCents()).isEqualTo(21_000_000L);
    assertThat(result.cost().travelersServed()).isEqualTo(4);
  }

  @Test
  void A_a_calculated_cost_never_takes_the_amount_of_the_request() {
    catalogued(21_000_000L);
    served(running.id(), 4);
    assertThatThrownBy(() -> record.execute(new RecordCost.Command(person, "SAL-001", KEY, "Boat", CostMode.CALCULATED, 1L, null, TRACE)))
        .isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("amountCents");
    assertThat(world.costRows).isEmpty();
  }

  @Test
  void A_a_calculated_cost_without_travelers_served_is_refused_and_writes_nothing() {
    catalogued(21_000_000L);
    assertThatThrownBy(() -> record.execute(calculation("SAL-001", KEY))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.costRows).isEmpty();
    assertThat(world.events).isEmpty();
    assertThat(world.costKeys).isEmpty();
  }

  @Test
  void A_without_parametrization_nothing_is_calculated_and_a_real_expense_needs_its_justification() {
    served(running.id(), 4);
    assertThatThrownBy(() -> record.execute(calculation("SAL-001", KEY))).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> record.execute(expense("SAL-001", KEY, 50_000L, null))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(world.costRows).isEmpty();
    RecordCost.Recorded result = record.execute(expense("SAL-001", KEY, 50_000L, "Paid at the dock, no catalogued cost"));
    assertThat(result.cost().withoutParametrization()).isTrue();
    assertThat(world.events.get(0).payload()).containsEntry("withoutParametrization", true);
  }

  @Test
  void A_a_departure_that_has_not_started_is_refused_before_asking_the_catalog() {
    departure(2, DepartureStatus.SCHEDULED, TENANT);
    departure(3, DepartureStatus.CANCELLED, TENANT);
    for (String code : new String[] {"SAL-002", "SAL-003"}) {
      assertThatThrownBy(() -> record.execute(expense(code, KEY, 50_000L, "x"))).isInstanceOf(InvalidStatusTransitionException.class);
    }
    assertThat(world.catalogCalls).isZero();
    assertThat(world.costRows).isEmpty();
  }

  @Test
  void A_a_finished_departure_still_accepts_a_cost() {
    Departure finished = running.finish(NOW, ACCOUNT, NOW, null);
    world.departures.put(finished.id(), finished);
    catalogued(21_000_000L);
    assertThat(record.execute(expense("SAL-001", KEY, 50_000L, null)).recorded()).isTrue();
  }

  @Test
  void A_the_catalog_down_writes_nothing() {
    world.catalogDown = true;
    assertThatThrownBy(() -> record.execute(expense("SAL-001", KEY, 50_000L, "x"))).isInstanceOf(DependencyUnavailableException.class);
    assertThat(world.costRows).isEmpty();
    assertThat(world.events).isEmpty();
    assertThat(world.costKeys).isEmpty();
  }

  @Test
  void A_a_repeat_answers_the_same_line_and_writes_once() {
    catalogued(21_000_000L);
    RecordCost.Recorded first = record.execute(expense("SAL-001", KEY, 50_000L, null));
    RecordCost.Recorded again = record.execute(expense("SAL-001", KEY, 50_000L, null));
    assertThat(again.recorded()).isFalse();
    assertThat(again.cost()).isEqualTo(first.cost());
    assertThat(world.costRows).hasSize(1);
    assertThat(world.events).hasSize(1);
  }

  @Test
  void A_a_key_used_for_another_departure_is_refused() {
    catalogued(21_000_000L);
    Departure other = departure(2, DepartureStatus.SCHEDULED, TENANT).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(other.id(), other);
    record.execute(expense("SAL-001", KEY, 50_000L, null));
    assertThatThrownBy(() -> record.execute(expense("SAL-002", KEY, 50_000L, null))).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void A_the_departure_of_another_tenant_is_not_found() {
    departure(3, DepartureStatus.IN_EXECUTION, OTHER_TENANT);
    assertThatThrownBy(() -> record.execute(expense("SAL-003", KEY, 50_000L, "x"))).isInstanceOf(DepartureNotFoundException.class);
    assertThatThrownBy(() -> list.execute(new ListCosts.Query(person, "SAL-003"))).isInstanceOf(DepartureNotFoundException.class);
  }

  @Test
  void A_an_invalid_body_is_400_even_on_a_departure_that_has_not_started_or_with_the_catalog_down() {
    departure(2, DepartureStatus.SCHEDULED, TENANT);
    assertThatThrownBy(() -> record.execute(new RecordCost.Command(person, "SAL-002", KEY, " ", CostMode.MANUAL, 100L, "x", TRACE)))
        .isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("concept");
    world.catalogDown = true;
    assertThatThrownBy(() -> record.execute(new RecordCost.Command(person, "SAL-001", KEY, "Fuel", CostMode.MANUAL, null, "x", TRACE)))
        .isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("amountCents");
    assertThatThrownBy(() -> record.execute(new RecordCost.Command(person, "SAL-001", KEY, "Fuel", CostMode.MANUAL, 100L, "j".repeat(501), TRACE)))
        .isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("justification");
    assertThat(world.catalogCalls).isZero();
  }

  @Test
  void A_the_travelers_served_are_read_after_the_lock_so_a_concurrent_save_is_counted() {
    catalogued(1_000L);
    served(running.id(), 4);
    world.onLock = () -> served(running.id(), 6);
    RecordCost.Recorded result = record.execute(calculation("SAL-001", KEY));
    assertThat(result.cost().travelersServed()).isEqualTo(6);
    assertThat(result.cost().amountCents()).isEqualTo(6_000L);
  }

  @Test
  void A_the_event_carries_no_free_text_and_the_same_key_in_another_tenant_is_another_key() {
    record.execute(expense("SAL-001", KEY, 50_000L, "Paid at the dock, no catalogued cost"));
    assertThat(world.events.get(0).payload()).doesNotContainKeys("concept", "justification");
    assertThat(world.findByKey(TENANT, KEY)).isPresent();
    assertThat(world.findByKey(OTHER_TENANT, KEY)).isEmpty();
  }

  @Test
  void A_the_sheet_does_not_mix_the_lines_of_other_departures() {
    Departure other = departure(2, DepartureStatus.SCHEDULED, TENANT).start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    world.departures.put(other.id(), other);
    record.execute(expense("SAL-001", KEY, 50_000L, "x"));
    record.execute(expense("SAL-002", "cost-key-00002", 70_000L, "x"));
    ListCosts.Sheet sheet = list.execute(new ListCosts.Query(person, "SAL-001"));
    assertThat(sheet.costs()).hasSize(1);
    assertThat(sheet.totalCents()).isEqualTo(50_000L);
  }

  @Test
  void A_the_mode_is_required() {
    assertThatThrownBy(() -> record.execute(new RecordCost.Command(person, "SAL-001", KEY, "Boat", null, 1L, null, TRACE)))
        .isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("mode");
  }

  @Test
  void A_the_total_is_the_sum_of_the_lines_with_the_travelers_served_and_the_catalogued_cost() {
    catalogued(21_000_000L);
    served(running.id(), 4);
    record.execute(calculation("SAL-001", KEY));
    record.execute(expense("SAL-001", "cost-key-00002", 50_000L, null));
    ListCosts.Sheet sheet = list.execute(new ListCosts.Query(person, "SAL-001"));
    assertThat(sheet.costs()).hasSize(2);
    assertThat(sheet.totalCents()).isEqualTo(84_050_000L);
    assertThat(sheet.travelersServed()).isEqualTo(4);
    assertThat(sheet.baseCostCents()).contains(21_000_000L);
  }

  @Test
  void A_a_tour_without_cost_says_so_and_the_catalog_down_is_unavailable() {
    assertThat(list.execute(new ListCosts.Query(person, "SAL-001")).baseCostCents()).isEmpty();
    world.catalogDown = true;
    assertThatThrownBy(() -> list.execute(new ListCosts.Query(person, "SAL-001"))).isInstanceOf(DependencyUnavailableException.class);
  }
}
