package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: the departures of this domain. Every read filters by tenant. */
public interface DepartureRepository {

  /** The next code {@code SAL-nnn} of the tenant; called inside the transaction that saves the departure. */
  String nextCode(UUID tenantId);

  void save(Departure departure);

  Optional<Departure> find(UUID tenantId, UUID id);

  Optional<Departure> findByCode(UUID tenantId, String code);

  /**
   * Like {@link #find} but the row stays locked until the end of the transaction, so a request that decides on the state and the
   * dates of a departure, or on its vehicles, reads them again here and nobody else changes them meanwhile (plan D-05). Every use
   * case that touches the vehicles of a departure takes this lock first, then the vehicles in id order.
   */
  Optional<Departure> findForUpdate(UUID tenantId, UUID id);

  /**
   * Replaces the start and the estimated end that were read ({@code from}) by the new ones ({@code to}), only if the departure
   * is still {@code SCHEDULED} and still has the dates that were read. False when it is not there, has left that state or was
   * changed meanwhile, so a change decided on a stale reading is never written and two changes never both win.
   */
  boolean updateSchedule(Departure from, Departure to);

  /**
   * Replaces the state and the execution that were read ({@code from}) by the new ones ({@code to}), only if the departure still has
   * the state that was read. False when it is not there or has left that state meanwhile, so two starts (or two ends) of the same
   * departure never both win.
   */
  boolean updateExecution(Departure from, Departure to);

  /**
   * Up to {@code limit} open departures ({@code SCHEDULED} or {@code IN_EXECUTION}) of any tenant whose estimated end is not after
   * {@code now} and that are not marked, oldest estimated end first, locked until the end of the transaction. A departure that
   * another transaction holds is skipped, so two overlapping asks never take the same one. Only the job that marks them reads across
   * tenants, and only through the service role.
   */
  List<Departure> lockOpenPastEnd(Instant now, int limit);

  /** One page of the departures of the tenant, most recent scheduled start first, optionally of one state. */
  Page<Departure> page(UUID tenantId, Optional<DepartureStatus> status, int page, int limit);
}
