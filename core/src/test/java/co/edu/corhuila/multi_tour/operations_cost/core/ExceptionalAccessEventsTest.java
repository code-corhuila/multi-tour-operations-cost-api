package co.edu.corhuila.multi_tour.operations_cost.core;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.ExceptionalAccess;
import co.edu.corhuila.multi_tour.operations_cost.core.security.ExceptionalAccessEvents;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spec 012 RN-SEC-012-07: the fact of a use says who, where and when, and never the data of the answer. */
class ExceptionalAccessEventsTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  private static final UUID AUTHORIZATION = UUID.fromString("3f2b8c1e-1111-4222-8333-444455556666");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
  private static final Instant AT = Instant.parse("2026-11-10T15:00:00Z");

  private static Caller caller(String subject) {
    return new Caller(subject, TENANT.toString(), Set.of(Role.PLATFORM_ADMINISTRATOR), Set.of(),
        new ExceptionalAccess("emergency", Set.of("OPERATIONS", "SENSITIVE"), AUTHORIZATION));
  }

  @Test
  void A_a_use_writes_its_fact_with_no_data() {
    OutboxEvent event = ExceptionalAccessEvents.used(UUID.randomUUID(), caller(ACCOUNT.toString()), "GET", "/api/v1/operations-cost/departures/{id}", "OPERATIONS", "trace-1", AT);

    assertThat(event.eventType()).isEqualTo("PlatformExceptionalAccessUsed");
    assertThat(event.aggregateId()).isEqualTo(AUTHORIZATION);
    assertThat(event.tenantId()).isEqualTo(TENANT);
    assertThat(event.actorId()).isEqualTo(ACCOUNT);
    assertThat(event.correlationId()).isEqualTo("trace-1");
    assertThat(event.occurredAt()).isEqualTo(AT);
    assertThat(event.payload()).containsOnlyKeys(
        "authorizationId", "platformAdministratorId", "tenantId", "method", "route", "area", "usedAt", "correlationId");
    assertThat(event.payload()).containsEntry("usedAt", "2026-11-10T15:00:00Z").containsEntry("area", "OPERATIONS");
  }

  @Test
  void A_a_subject_that_is_not_a_uuid_stays_in_the_payload_and_leaves_the_actor_empty() {
    OutboxEvent event = ExceptionalAccessEvents.used(UUID.randomUUID(), caller("platform-admin-1"), "GET", "/x", "OPERATIONS", null, AT);

    assertThat(event.actorId()).isNull();
    assertThat(event.payload()).containsEntry("platformAdministratorId", "platform-admin-1");
  }
}
