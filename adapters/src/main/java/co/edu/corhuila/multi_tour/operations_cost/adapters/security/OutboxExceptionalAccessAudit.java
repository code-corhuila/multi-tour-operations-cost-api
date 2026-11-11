package co.edu.corhuila.multi_tour.operations_cost.adapters.security;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdGenerator;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Outbox;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.ExceptionalAccessAudit;
import co.edu.corhuila.multi_tour.operations_cost.core.security.ExceptionalAccessEvents;
import java.time.Clock;
import org.slf4j.MDC;

/** Writes the fact of a use in the outbox, from where the relay carries it to the audit (ADR-004 D4). */
public final class OutboxExceptionalAccessAudit implements ExceptionalAccessAudit {

  private final Outbox outbox;
  private final IdGenerator ids;
  private final Clock clock;

  public OutboxExceptionalAccessAudit(Outbox outbox, IdGenerator ids, Clock clock) {
    this.outbox = outbox;
    this.ids = ids;
    this.clock = clock;
  }

  @Override
  public void used(Caller who, String method, String route, String area) {
    outbox.add(ExceptionalAccessEvents.used(ids.next(), who, method, route, area, MDC.get("traceId"), clock.instant()));
  }
}
