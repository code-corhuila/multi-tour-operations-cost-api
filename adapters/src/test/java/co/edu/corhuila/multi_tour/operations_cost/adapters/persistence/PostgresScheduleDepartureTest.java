package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ScheduleDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** The use case over the real engine: what the fakes of the core cannot show (RN-OPS-API-13, -14, -15; AC-API-02.12, -02.14). */
class PostgresScheduleDepartureTest extends PostgresTest {

  private static final Instant START = Instant.now().plus(Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

  private static Caller admin(UUID tenant) {
    return new Caller(ACCOUNT.toString(), tenant.toString(), Set.of(Role.TENANT_ADMINISTRATOR));
  }

  private static ScheduleDeparture.Command command(UUID tenant, String key) {
    return new ScheduleDeparture.Command(admin(tenant), key, TOUR, START, START.plus(Duration.ofHours(8)), "America/Bogota", "trace-it");
  }

  @Test
  void I_departure_and_event_are_written_together_and_the_event_waits_to_be_delivered() {
    UUID tenant = UUID.randomUUID();
    Departure departure = useCase(outbox).execute(command(tenant, "key-" + UUID.randomUUID())).departure();

    assertThat(departures.find(tenant, departure.id())).contains(departure);
    assertThat(departure.status()).isEqualTo(DepartureStatus.SCHEDULED);
    var event = jdbc.sql("SELECT event_type, status, actor_id, correlation_id, payload->>'serviceId' AS service FROM outbox_event WHERE tenant_id = :t")
        .param("t", tenant).query().singleRow();
    assertThat(event).containsEntry("event_type", "SalidaScheduled").containsEntry("status", "PENDING")
        .containsEntry("correlation_id", "trace-it").containsEntry("service", TOUR.toString());
    assertThat(event.get("actor_id").toString()).isEqualTo(ACCOUNT.toString());
  }

  @Test
  void I_a_failed_write_of_the_event_leaves_no_departure_and_no_key() {
    UUID tenant = UUID.randomUUID();
    var failing = new co.edu.corhuila.multi_tour.operations_cost.core.departure.Outbox() {
      @Override
      public void add(co.edu.corhuila.multi_tour.operations_cost.core.departure.OutboxEvent event) {
        throw new IllegalStateException("the outbox is down");
      }

      @Override
      public boolean waiting(UUID tenantId, UUID aggregateId, String eventType) {
        return false;
      }
    };
    assertThatThrownBy(() -> useCase(failing).execute(command(tenant, "key-" + UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("salida", tenant)).isZero();
    assertThat(count("idempotency_key", tenant)).isZero();
    assertThat(count("outbox_event", tenant)).isZero();
  }

  @Test
  void I_two_creations_at_once_get_different_codes() throws Exception {
    UUID tenant = UUID.randomUUID();
    int requests = 8;
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    try {
      List<Callable<ScheduleDeparture.Scheduled>> work = new java.util.ArrayList<>();
      for (int i = 0; i < requests; i++) {
        String key = "key-" + i + "-" + UUID.randomUUID();
        work.add(() -> useCase(outbox).execute(command(tenant, key)));
      }
      List<String> codes = new java.util.ArrayList<>();
      for (Future<ScheduleDeparture.Scheduled> done : pool.invokeAll(work)) {
        codes.add(done.get().departure().code());
      }
      assertThat(codes).doesNotHaveDuplicates().hasSize(requests);
      assertThat(codes).containsExactlyInAnyOrder("SAL-001", "SAL-002", "SAL-003", "SAL-004", "SAL-005", "SAL-006", "SAL-007", "SAL-008");
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("salida", tenant)).isEqualTo(requests);
    assertThat(count("outbox_event", tenant)).isEqualTo(requests);
  }

  @Test
  void I_requests_at_once_with_the_same_key_create_one_departure() throws Exception {
    UUID tenant = UUID.randomUUID();
    String key = "same-key-" + UUID.randomUUID();
    int requests = 6;
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    try {
      java.util.concurrent.CountDownLatch together = new java.util.concurrent.CountDownLatch(requests);
      List<Callable<ScheduleDeparture.Scheduled>> work = new java.util.ArrayList<>();
      for (int i = 0; i < requests; i++) {
        work.add(() -> {
          together.countDown();
          together.await(); // all at once, so they all find no key yet
          return useCase(outbox).execute(command(tenant, key));
        });
      }
      List<ScheduleDeparture.Scheduled> answers = new java.util.ArrayList<>();
      for (Future<ScheduleDeparture.Scheduled> done : pool.invokeAll(work)) {
        answers.add(done.get());
      }
      assertThat(answers).extracting(a -> a.departure().id()).containsOnly(answers.get(0).departure().id());
      assertThat(answers).filteredOn(ScheduleDeparture.Scheduled::created).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("salida", tenant)).isEqualTo(1);
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
    assertThat(count("idempotency_key", tenant)).isEqualTo(1);
  }

  @Test
  void I_the_code_and_the_departure_belong_to_the_tenant() {
    UUID one = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    Departure first = useCase(outbox).execute(command(one, "key-" + UUID.randomUUID())).departure();
    Departure second = useCase(outbox).execute(command(other, "key-" + UUID.randomUUID())).departure();
    assertThat(first.code()).isEqualTo("SAL-001");
    assertThat(second.code()).isEqualTo("SAL-001");
    assertThat(departures.find(other, first.id())).isEmpty();
    assertThat(departures.find(one, first.id())).contains(first);
  }

  @Test
  void I_the_same_key_in_two_tenants_creates_two_departures() {
    UUID one = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    String key = "shared-key-" + UUID.randomUUID();
    assertThat(useCase(outbox).execute(command(one, key)).created()).isTrue();
    assertThat(useCase(outbox).execute(command(other, key)).created()).isTrue();
  }

  @Test
  void I_the_application_user_cannot_delete_a_departure() {
    UUID tenant = UUID.randomUUID();
    Departure departure = useCase(outbox).execute(command(tenant, "key-" + UUID.randomUUID())).departure();
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM salida WHERE salida_id = :id").param("id", departure.id()).update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThat(count("salida", tenant)).isEqualTo(1);
  }
}
