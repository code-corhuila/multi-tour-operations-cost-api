package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.UUID;

/**
 * What the catalog of commercial-reservation says about an item: its kind, whether it is offered, its capacity and whether the
 * tour requires transport, as the catalog says it (HU-OPS-002: then its vehicles decide the seats that can be sold).
 */
public record CatalogTour(UUID serviceId, Type type, boolean active, int capacity, boolean transportRequired) {

  public CatalogTour(UUID serviceId, Type type, boolean active, int capacity) {
    this(serviceId, type, active, capacity, false);
  }

  public enum Type {
    TOUR,
    TRANSPORTATION
  }
}
