package co.edu.corhuila.multi_tour.operations_cost.core.security;

import java.util.UUID;

/** Port: the ids of the events already applied. */
public interface ProcessedEvents {

  /** Marks the id; false when it was already marked. */
  boolean markIfNew(UUID eventId);
}
