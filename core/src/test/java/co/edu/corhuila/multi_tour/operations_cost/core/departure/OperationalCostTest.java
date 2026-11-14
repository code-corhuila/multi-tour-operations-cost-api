package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The operational cost of a departure with no framework and no double (api spec 007, RN-OPS-API-70 and -72 to -74). */
class OperationalCostTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final UUID ID = new UUID(0, 1);
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID DEPARTURE = new UUID(0, 1001);
  private static final UUID BY = new UUID(0, 0xcc);

  private static Departure inState(DepartureStatus status) {
    return new Departure(DEPARTURE, TENANT, "SAL-001", new UUID(0, 0xbb), NOW.minus(Duration.ofHours(4)), NOW.plus(Duration.ofHours(4)),
        "America/Bogota", status, BY, NOW.minus(Duration.ofDays(2)));
  }

  private static OperationalCost calculated(Long base, int served) {
    return OperationalCost.calculated(ID, TENANT, DEPARTURE, "Boat and guide", base, served, BY, NOW);
  }

  private static OperationalCost manual(Long amount, boolean tourHasCost, String justification) {
    return OperationalCost.manual(ID, TENANT, DEPARTURE, "Fuel of the boat", amount, tourHasCost, justification, BY, NOW);
  }

  @Test
  void D_costs_only_once_the_departure_started() {
    assertThatCode(() -> inState(DepartureStatus.IN_EXECUTION).acceptCosts()).doesNotThrowAnyException();
    assertThatCode(() -> inState(DepartureStatus.FINISHED).acceptCosts()).doesNotThrowAnyException();
    for (DepartureStatus other : new DepartureStatus[] {DepartureStatus.SCHEDULED, DepartureStatus.CANCELLED}) {
      assertThatThrownBy(() -> inState(other).acceptCosts()).isInstanceOf(InvalidStatusTransitionException.class);
    }
  }

  @Test
  void D_calculated_is_the_unit_cost_times_the_travelers_served() {
    OperationalCost cost = calculated(21_000_000L, 5);
    assertThat(cost.mode()).isEqualTo(CostMode.CALCULATED);
    assertThat(cost.amountCents()).isEqualTo(105_000_000L);
    assertThat(cost.unitCostCents()).isEqualTo(21_000_000L);
    assertThat(cost.travelersServed()).isEqualTo(5);
    assertThat(cost.withoutParametrization()).isFalse();
    assertThat(cost.justification()).isNull();
    assertThat(cost.recordedBy()).isEqualTo(BY);
    assertThat(cost.recordedAt()).isEqualTo(NOW);
  }

  @Test
  void D_calculated_without_travelers_served_or_with_an_overflow_is_refused() {
    assertThatThrownBy(() -> calculated(1000L, 0)).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> calculated(Long.MAX_VALUE, 2)).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void D_without_parametrization_nothing_is_calculated() {
    assertThatThrownBy(() -> calculated(null, 5)).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> calculated(0L, 5)).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void D_without_parametrization_a_real_expense_needs_a_justification() {
    OperationalCost cost = manual(50_000L, false, "Paid at the dock, no catalogued cost");
    assertThat(cost.mode()).isEqualTo(CostMode.MANUAL);
    assertThat(cost.withoutParametrization()).isTrue();
    assertThat(cost.justification()).isEqualTo("Paid at the dock, no catalogued cost");
    assertThat(cost.unitCostCents()).isNull();
    assertThat(cost.travelersServed()).isNull();
    assertThatThrownBy(() -> manual(50_000L, false, null)).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> manual(50_000L, false, "  ")).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> manual(50_000L, false, "j".repeat(501))).isInstanceOf(InvalidDataException.class);
  }

  @Test
  void D_with_parametrization_a_real_expense_takes_no_justification() {
    OperationalCost cost = manual(50_000L, true, null);
    assertThat(cost.withoutParametrization()).isFalse();
    assertThat(cost.justification()).isNull();
    assertThatThrownBy(() -> manual(50_000L, true, "not needed")).isInstanceOf(InvalidDataException.class)
        .extracting("field").isEqualTo("justification");
  }

  @Test
  void D_amount_and_concept_are_judged() {
    assertThatThrownBy(() -> manual(null, true, null)).isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("amountCents");
    assertThatThrownBy(() -> manual(0L, true, null)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> manual(-1L, true, null)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> OperationalCost.manual(ID, TENANT, DEPARTURE, " ", 100L, true, null, BY, NOW)).isInstanceOf(InvalidDataException.class)
        .extracting("field").isEqualTo("concept");
    assertThatThrownBy(() -> OperationalCost.manual(ID, TENANT, DEPARTURE, "c".repeat(201), 100L, true, null, BY, NOW))
        .isInstanceOf(InvalidDataException.class);
    assertThatCode(() -> OperationalCost.manual(ID, TENANT, DEPARTURE, "c".repeat(200), 100L, true, null, BY, NOW)).doesNotThrowAnyException();
  }

  @Test
  void D_the_limits_are_measured_after_the_spaces_around_the_text_are_taken_off() {
    assertThatCode(() -> OperationalCost.manual(ID, TENANT, DEPARTURE, " " + "c".repeat(200) + " ", 100L, true, null, BY, NOW)).doesNotThrowAnyException();
    assertThatCode(() -> manual(50_000L, false, " " + "j".repeat(500) + " ")).doesNotThrowAnyException();
    // blank is blank whatever its length: a business refusal, not a field error
    assertThatThrownBy(() -> manual(50_000L, false, " ".repeat(600))).isInstanceOf(BusinessRuleViolationException.class);
    assertThat(manual(50_000L, false, " why ").justification()).isEqualTo("why");
  }

  @Test
  void D_a_catalogued_cost_that_is_not_above_zero_is_no_cost_at_all() {
    UUID tour = new UUID(0, 0xbb);
    assertThat(new CatalogTour(tour, CatalogTour.Type.TOUR, true, 18, true, 0L).baseOperationalCostCents()).isEmpty();
    assertThat(new CatalogTour(tour, CatalogTour.Type.TOUR, true, 18, true, -5L).baseOperationalCostCents()).isEmpty();
  }

  @Test
  void D_the_catalog_says_the_cost_of_a_tour_or_that_it_has_none() {
    UUID tour = new UUID(0, 0xbb);
    assertThat(new CatalogTour(tour, CatalogTour.Type.TOUR, true, 18, true, 21_000_000L).baseOperationalCostCents()).contains(21_000_000L);
    assertThat(new CatalogTour(tour, CatalogTour.Type.TOUR, true, 18, true).baseOperationalCostCents()).isEmpty();
    assertThat(new CatalogTour(tour, CatalogTour.Type.TOUR, true, 18).baseOperationalCostCents()).isEmpty();
  }
}
