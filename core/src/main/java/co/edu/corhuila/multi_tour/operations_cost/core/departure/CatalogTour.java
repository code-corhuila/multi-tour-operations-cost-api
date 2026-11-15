package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.Optional;
import java.util.UUID;

/**
 * What the catalog of commercial-reservation says about an item: its kind, whether it is offered, its capacity, whether the tour
 * requires transport, as the catalog says it (HU-OPS-002: then its vehicles decide the seats that can be sold), and the operational cost
 * that it parametrizes, when it does (HU-OPS-007; absent means the tour has none).
 */
public record CatalogTour(
    UUID serviceId, Type type, boolean active, int capacity, boolean transportRequired, Optional<Long> baseOperationalCostCents) {

  /** A cost that is not above 0 is no parametrization: one criterion for "the tour has a parametrized cost" everywhere. */
  public CatalogTour {
    baseOperationalCostCents = baseOperationalCostCents.filter(cost -> cost > 0);
  }

  public CatalogTour(UUID serviceId, Type type, boolean active, int capacity, boolean transportRequired, long baseOperationalCostCents) {
    this(serviceId, type, active, capacity, transportRequired, Optional.of(baseOperationalCostCents));
  }

  public CatalogTour(UUID serviceId, Type type, boolean active, int capacity, boolean transportRequired) {
    this(serviceId, type, active, capacity, transportRequired, Optional.empty());
  }

  public CatalogTour(UUID serviceId, Type type, boolean active, int capacity) {
    this(serviceId, type, active, capacity, false);
  }

  public enum Type {
    TOUR,
    TRANSPORTATION
  }
}
