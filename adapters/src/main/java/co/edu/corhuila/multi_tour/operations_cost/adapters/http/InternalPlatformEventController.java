package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.AccountSessionsRevoked;
import co.edu.corhuila.multi_tour.operations_cost.core.security.ApplyAccountSessionsRevoked;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where job 7 delivers {@code AccountSessionsRevoked} (HU-SEC-005 AC4, api spec 013, operation {@code receivePlatformEvent}). It has no
 * tenant in the path because the account of a Platform Administrator has none. Only the SERVICE role opens it ({@code RouteAuthorization})
 * and the gateway never routes it (ADR-004 D5); the 16 KB are the {@code BodyLimitFilter}'s. The body is read as a tree because the
 * contract tolerates fields it does not name.
 */
@RestController
class InternalPlatformEventController {

  private static final String EVENT_TYPE = "AccountSessionsRevoked";

  private final ApplyAccountSessionsRevoked apply;

  InternalPlatformEventController(ApplyAccountSessionsRevoked apply) {
    this.apply = apply;
  }

  @PostMapping(path = "/api/v1/operations-cost/internal/events", consumes = "application/json")
  ResponseEntity<Void> receive(@RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new InvalidDataException("body", "The body is a JSON object");
    }
    if (!EVENT_TYPE.equals(body.path("eventType").textValue())) {
      throw new InvalidDataException("eventType", "This route receives only AccountSessionsRevoked");
    }
    UUID eventId = uuid(body, "eventId");
    if (key == null || !key.equalsIgnoreCase(eventId.toString())) {
      throw new InvalidDataException("Idempotency-Key", "The key is the eventId of the body");
    }
    JsonNode version = body.path("eventVersion");
    if (!version.isIntegralNumber() || version.asLong() < 1) {
      throw new InvalidDataException("eventVersion", "The version is an integer of 1 or more");
    }
    instant(body, "occurredAt");
    JsonNode payload = body.path("payload");
    if (!payload.isObject()) {
      throw new InvalidDataException("payload", "The payload is required");
    }
    UUID accountId = uuid(payload, "accountId");
    if (!uuid(body, "aggregateId").equals(accountId)) {
      throw new InvalidDataException("aggregateId", "The aggregate is the account of the payload");
    }
    UUID tenantId = payload.path("tenantId").isMissingNode() || payload.path("tenantId").isNull() ? null : uuid(payload, "tenantId");
    ApplyAccountSessionsRevoked.Outcome outcome =
        apply.execute(new AccountSessionsRevoked(eventId, accountId, tenantId, instant(payload, "revokedFrom")));
    return ResponseEntity.status(outcome == ApplyAccountSessionsRevoked.Outcome.APPLIED ? HttpStatus.CREATED : HttpStatus.OK).build();
  }

  private static String text(JsonNode node, String field) {
    String value = node.path(field).textValue();
    if (value == null) {
      throw new InvalidDataException(field, "The field " + field + " is required");
    }
    return value;
  }

  /** In its canonical 36 characters: the lax parser of the platform would take odd forms. */
  private static UUID uuid(JsonNode node, String field) {
    String value = text(node, field);
    try {
      UUID id = UUID.fromString(value);
      if (id.toString().equalsIgnoreCase(value)) {
        return id;
      }
    } catch (IllegalArgumentException notAUuid) {
      // told below
    }
    throw new InvalidDataException(field, "The field " + field + " is not a valid identifier");
  }

  private static Instant instant(JsonNode node, String field) {
    try {
      return Instant.parse(text(node, field));
    } catch (DateTimeParseException notAnInstant) {
      throw new InvalidDataException(field, "The field " + field + " is not an RFC 3339 instant");
    }
  }
}
