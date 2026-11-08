package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.UUID;

/** What the catalog of commercial-reservation says about an item: its kind, whether it is offered and its capacity. */
public record CatalogTour(UUID serviceId, Type type, boolean active, int capacity) {

  public enum Type {
    TOUR,
    TRANSPORTATION
  }
}
