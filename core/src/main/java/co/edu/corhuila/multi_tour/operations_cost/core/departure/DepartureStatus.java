package co.edu.corhuila.multi_tour.operations_cost.core.departure;

/**
 * The four states of the PDR (section 16). A departure is born SCHEDULED; the moves out of it belong to HU-OPS-003 and
 * HU-OPS-006. The names are the ones the database accepts.
 */
public enum DepartureStatus {
  SCHEDULED,
  IN_EXECUTION,
  FINISHED,
  CANCELLED
}
