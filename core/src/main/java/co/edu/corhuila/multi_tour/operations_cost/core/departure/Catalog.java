package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.Optional;
import java.util.UUID;

/** Port: the catalog of the tenant, owned by commercial-reservation and read through its API (ADR-004 D4). */
public interface Catalog {

  /** The item, or empty when the tenant has none with that id. Throws {@link DependencyUnavailableException} when it cannot say. */
  Optional<CatalogTour> tour(UUID serviceId);
}
