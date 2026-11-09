package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.util.UUID;

/**
 * A real operational cost of a departure (HU-OPS-007, RN-OPS-API-72 to -74): calculated from the cost that the catalog parametrizes for
 * the tour and the travelers actually served, or the real expense that a person paid. When the tour has no parametrized cost nothing is
 * calculated and the expense needs its written justification. It is a value, kept apart from the commercial value of any reservation.
 */
public record OperationalCost(
    UUID costId, UUID tenantId, UUID departureId, String concept, long amountCents, CostMode mode, Long unitCostCents, Integer travelersServed,
    boolean withoutParametrization, String justification, UUID recordedBy, Instant recordedAt) {

  public static final int CONCEPT_MAX = 200;
  public static final int JUSTIFICATION_MAX = 500;

  /** The catalogued cost times the travelers actually served; both are kept on the line. */
  public static OperationalCost calculated(
      UUID costId, UUID tenantId, UUID departureId, String concept, Long catalogued, int travelersServed, UUID by, Instant at) {
    String text = concept(concept);
    if (catalogued == null || catalogued <= 0) {
      throw new BusinessRuleViolationException("The tour has no parametrized cost: record the real expense with its justification");
    }
    if (travelersServed < 1) {
      throw new BusinessRuleViolationException("No travelers served are recorded for this departure: record the services first");
    }
    long amount;
    try {
      amount = Math.multiplyExact(catalogued, (long) travelersServed);
    } catch (ArithmeticException overflow) {
      throw new BusinessRuleViolationException("The cost is too large to be recorded");
    }
    return new OperationalCost(costId, tenantId, departureId, text, amount, CostMode.CALCULATED, catalogued, travelersServed, false, null, by, at);
  }

  /** The real expense that was paid. Without a parametrized cost on the tour it needs its justification; with one it takes none. */
  public static OperationalCost manual(
      UUID costId, UUID tenantId, UUID departureId, String concept, Long amountCents, boolean tourHasParametrizedCost, String justification, UUID by,
      Instant at) {
    String text = concept(concept);
    if (amountCents == null || amountCents <= 0) {
      throw new InvalidDataException("amountCents", "The amount is missing or not above 0");
    }
    if (tourHasParametrizedCost) {
      if (justification != null) {
        throw new InvalidDataException("justification", "The tour has a parametrized cost: the justification is only for a cost without one");
      }
      return new OperationalCost(costId, tenantId, departureId, text, amountCents, CostMode.MANUAL, null, null, false, null, by, at);
    }
    String why = justification == null ? "" : justification.strip();
    if (why.isEmpty()) {
      throw new BusinessRuleViolationException("The tour has no parametrized cost: the amount needs its written justification");
    }
    if (why.length() > JUSTIFICATION_MAX) {
      throw new InvalidDataException("justification", "The justification has 1 to " + JUSTIFICATION_MAX + " characters");
    }
    return new OperationalCost(costId, tenantId, departureId, text, amountCents, CostMode.MANUAL, null, null, true, why, by, at);
  }

  private static String concept(String concept) {
    String text = concept == null ? "" : concept.strip();
    if (text.isEmpty() || text.length() > CONCEPT_MAX) {
      throw new InvalidDataException("concept", "The concept has 1 to " + CONCEPT_MAX + " characters");
    }
    return text;
  }
}
