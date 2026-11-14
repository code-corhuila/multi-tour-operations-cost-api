package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.OutboxRelay;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.StoredEvent;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The outbox as job 7 (the event relay) reads it, as every publisher offers it (ADR-004 D4). Only the SERVICE role opens these
 * routes ({@code RouteAuthorization}) and the gateway never routes them (ADR-004 D5). The entity is never serialized: the
 * answers are built here.
 */
@RestController
@RequestMapping("/api/v1/operations-cost/internal/outbox")
class InternalOutboxController {

  private static final Set<String> QUERY = Set.of("status", "page", "limit");

  /** An event as job 7 reads it. */
  record EventResponse(
      UUID eventId, String eventType, int eventVersion, UUID tenantId, UUID aggregateId, UUID actorId, String reason,
      Map<String, Object> payload, String correlationId, Instant occurredAt, Instant publishedAt, String status, int attempts,
      String lastError, Instant nextAttemptAt) {

    static EventResponse of(StoredEvent e) {
      return new EventResponse(e.eventId(), e.eventType(), e.eventVersion(), e.tenantId(), e.aggregateId(), e.actorId(), e.reason(),
          e.payload(), e.correlationId(), e.occurredAt(), e.publishedAt(), e.status().name(), e.attempts(), e.lastError(),
          e.nextAttemptAt());
    }
  }

  record Meta(int page, int limit, long total, int totalPages) {}

  record Listing(List<EventResponse> data, Meta meta) {}

  /** The body of a failed delivery: the error and nothing else. */
  record AttemptRequest(String error) {}

  private final OutboxRelay relay;

  InternalOutboxController(OutboxRelay relay) {
    this.relay = relay;
  }

  @GetMapping
  Listing list(HttpServletRequest request) {
    // a parameter nobody declared, or one given twice, is refused and not ignored
    for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
      if (!QUERY.contains(parameter.getKey()) || parameter.getValue().length != 1) {
        throw new InvalidDataException(parameter.getKey(), "The parameter " + parameter.getKey() + " is not valid");
      }
    }
    String status = request.getParameter("status");
    if (status != null && !status.equals("PENDING")) {
      throw new InvalidDataException("status", "Only PENDING is read");
    }
    Page<StoredEvent> page = relay.listDue(number(request, "page", 1), number(request, "limit", 20));
    return new Listing(page.data().stream().map(EventResponse::of).toList(),
        new Meta(page.page(), page.limit(), page.total(), page.totalPages()));
  }

  @PostMapping("/{eventId}/published")
  EventResponse published(@PathVariable UUID eventId) {
    return EventResponse.of(relay.markPublished(eventId));
  }

  @PostMapping(path = "/{eventId}/attempts", consumes = "application/json")
  EventResponse attempt(@PathVariable UUID eventId, @RequestBody AttemptRequest body) {
    return EventResponse.of(relay.recordFailedAttempt(eventId, body.error()));
  }

  private static int number(HttpServletRequest request, String name, int byDefault) {
    String value = request.getParameter(name);
    if (value == null) {
      return byDefault;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException notANumber) {
      throw new InvalidDataException(name, "The parameter " + name + " is not a number");
    }
  }
}
