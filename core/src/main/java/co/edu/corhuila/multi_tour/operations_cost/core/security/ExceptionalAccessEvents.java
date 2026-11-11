package co.edu.corhuila.multi_tour.operations_cost.core.security;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The fact of each use of the token of an exceptional or emergency access (spec 012 RN-SEC-012-07): who, where and when, never data of the answer. */
public final class ExceptionalAccessEvents {

  private ExceptionalAccessEvents() {}

  public static OutboxEvent used(UUID eventId, Caller who, String method, String route, String area, String trace, Instant at) {
    UUID tenant = UUID.fromString(who.tenantId());
    UUID authorization = who.exceptional().authorizationId();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("authorizationId", authorization.toString());
    payload.put("platformAdministratorId", who.subject());
    payload.put("tenantId", tenant.toString());
    payload.put("method", method);
    payload.put("route", route);
    payload.put("area", area);
    payload.put("usedAt", at.toString());
    payload.put("correlationId", trace);
    return new OutboxEvent(eventId, "PlatformExceptionalAccessUsed", authorization, tenant, accountOf(who.subject()), trace, payload, at);
  }

  private static UUID accountOf(String subject) {
    try {
      return UUID.fromString(subject);
    } catch (IllegalArgumentException notAnAccount) {
      return null;
    }
  }
}
