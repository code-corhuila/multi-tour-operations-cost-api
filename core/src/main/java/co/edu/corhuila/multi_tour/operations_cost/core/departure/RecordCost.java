package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Records a real operational cost of a departure that started (HU-OPS-007, api 007). The flow is the one of the other recordings: a
 * repeated key answers the line it created; the state is judged before another domain is asked anything; the catalogued cost of the
 * tour is read before the transaction opens, so a failure there writes nothing; inside it the departure is locked and judged again,
 * and the line and its event are written together. A cost is kept apart from the reservations: nothing of them is touched. The travelers
 * served are this service's own records, so the reservations are not read. The route judges who may call.
 */
public final class RecordCost {

  private final DepartureRepository departures;
  private final OperationalCosts costs;
  private final ServiceRecords services;
  private final Catalog catalog;
  private final Outbox outbox;
  private final Transaction transaction;
  private final IdGenerator ids;
  private final Clock clock;

  public RecordCost(
      DepartureRepository departures, OperationalCosts costs, ServiceRecords services, Catalog catalog, Outbox outbox, Transaction transaction,
      IdGenerator ids, Clock clock) {
    this.departures = departures;
    this.costs = costs;
    this.services = services;
    this.catalog = catalog;
    this.outbox = outbox;
    this.transaction = transaction;
    this.ids = ids;
    this.clock = clock;
  }

  /** {@code amountCents} is for a real expense only; a calculated cost takes its amount from the catalog and the travelers served. */
  public record Command(
      Caller caller, String departureCode, String idempotencyKey, String concept, CostMode mode, Long amountCents, String justification,
      String correlationId) {}

  /** The line, and whether this request recorded it (false for a repeat). */
  public record Recorded(OperationalCost cost, boolean recorded) {}

  public Recorded execute(Command command) {
    UUID tenantId = Requests.uuid(command.caller().tenantId());
    UUID account = Requests.uuid(command.caller().subject());
    Requests.requireKey(command.idempotencyKey());
    Requests.requireTrace(command.correlationId());
    if (command.mode() == null) {
      throw new InvalidDataException("mode", "The field mode is missing or not valid");
    }
    if (command.mode() == CostMode.CALCULATED && (command.amountCents() != null || command.justification() != null)) {
      throw new InvalidDataException("amountCents", "A calculated cost takes no amount nor justification: the service calculates it");
    }
    OperationalCost.judgeRequest(command.concept(), command.mode(), command.amountCents(), command.justification());
    Departure departure =
        departures.findByCode(tenantId, command.departureCode()).orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
    Optional<OperationalCost> previous = costs.findByKey(tenantId, command.idempotencyKey());
    if (previous.isPresent()) {
      return repeat(departure, previous.get());
    }
    // the state is judged before another domain is asked anything
    departure.acceptCosts();
    Optional<CatalogTour> tour = catalog.tour(departure.serviceId());
    Optional<Long> catalogued = tour.flatMap(CatalogTour::baseOperationalCostCents);
    Instant now = clock.instant();
    OperationalCost cost;
    try {
      cost = transaction.run(() -> {
        Departure current =
            departures.findForUpdate(tenantId, departure.id()).orElseThrow(() -> new DepartureNotFoundException(command.departureCode()));
        current.acceptCosts();
        // the travelers served are this service's own records and a save of services may be running: they are read once the departure is locked
        OperationalCost line = build(command, current, catalogued, tenantId, account, now);
        costs.add(line, command.idempotencyKey());
        outbox.add(CostEvents.recorded(ids.next(), line, command.correlationId(), now));
        return line;
      });
    } catch (InvalidStatusTransitionException | DuplicateKeyException lostTheRace) {
      // another request with the same key committed first and this one rolled back: its line is the answer
      Optional<OperationalCost> winner = costs.findByKey(tenantId, command.idempotencyKey());
      if (winner.isEmpty()) {
        throw lostTheRace;
      }
      return repeat(departure, winner.get());
    }
    return new Recorded(cost, true);
  }

  private OperationalCost build(Command command, Departure departure, Optional<Long> catalogued, UUID tenantId, UUID account, Instant at) {
    UUID costId = ids.next();
    if (command.mode() == CostMode.CALCULATED) {
      int served = ServiceRecord.realBase(services.recordedFor(tenantId, departure.id()));
      return OperationalCost.calculated(costId, tenantId, departure.id(), command.concept(), catalogued.orElse(null), served, account, at);
    }
    return OperationalCost.manual(
        costId, tenantId, departure.id(), command.concept(), command.amountCents(), catalogued.isPresent(), command.justification(), account, at);
  }

  /** A repeat answers the line that the key made, and only for the departure it was made for: a key is not good for another departure. */
  private Recorded repeat(Departure asked, OperationalCost made) {
    if (!made.departureId().equals(asked.id())) {
      throw new BusinessRuleViolationException("The Idempotency-Key was already used for another departure");
    }
    return new Recorded(made, false);
  }
}
