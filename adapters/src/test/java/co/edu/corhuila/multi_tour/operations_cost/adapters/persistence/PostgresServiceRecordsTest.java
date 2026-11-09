package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdempotencyKeys;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServiceRecord;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServiceResult;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What each reservation of a departure received, over the real engine (api 006 S6-03, RN-OPS-API-66 and -67). */
class PostgresServiceRecordsTest extends PostgresTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final UUID RESERVATION_1 = UUID.randomUUID();
  private static final UUID RESERVATION_2 = UUID.randomUUID();
  private static final UUID RECORDER = UUID.fromString("00000000-0000-4000-8000-0000000000dd");

  private static JdbcServiceRecords records() {
    return new JdbcServiceRecords(jdbc);
  }

  private static Departure running(UUID tenant) {
    Instant start = NOW.minus(Duration.ofHours(3));
    Departure created = new Departure(UUID.randomUUID(), tenant, departures.nextCode(tenant), TOUR, start, start.plus(Duration.ofHours(8)),
        "America/Bogota", DepartureStatus.SCHEDULED, ACCOUNT, start.minus(Duration.ofDays(2)));
    departures.save(created);
    Departure started = created.start(NOW.minus(Duration.ofHours(2)), RECORDER, NOW);
    departures.updateExecution(created, started);
    return started;
  }

  private static ServiceRecord delivered(UUID reservation, int served, String cause) {
    return new ServiceRecord(reservation, ServiceResult.DELIVERED, served, cause, false);
  }

  @Test
  void I_services_and_key_are_written_together() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      records().saveAll(tenant, departure.id(), List.of(delivered(RESERVATION_1, 4, null)), RECORDER, NOW);
      keys.register(tenant, IdempotencyKeys.SAVE_SERVICES, "services-key-01", departure.id());
      return null;
    });
    assertThat(records().recordedFor(tenant, departure.id())).containsExactly(delivered(RESERVATION_1, 4, null));
    assertThat(keys.departureOf(tenant, IdempotencyKeys.SAVE_SERVICES, "services-key-01")).contains(departure.id());
  }

  @Test
  void I_a_failure_after_the_records_leaves_neither_records_nor_key() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    assertThatThrownBy(() -> transaction.run(() -> {
      records().saveAll(tenant, departure.id(), List.of(delivered(RESERVATION_1, 4, null)), RECORDER, NOW);
      throw new IllegalStateException("the outbox is down");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(records().recordedFor(tenant, departure.id())).isEmpty();
  }

  @Test
  void I_who_recorded_the_services_and_when_are_kept() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      records().saveAll(tenant, departure.id(), List.of(delivered(RESERVATION_1, 4, null)), RECORDER, NOW);
      return null;
    });
    var row = jdbc.sql("SELECT recorded_by, recorded_at FROM salida_service WHERE salida_id = :id AND reservation_id = :reservation")
        .param("id", departure.id()).param("reservation", RESERVATION_1).query().singleRow();
    assertThat(row.get("recorded_by")).isEqualTo(RECORDER);
    assertThat(((java.sql.Timestamp) row.get("recorded_at")).toInstant()).isEqualTo(NOW);
  }

  @Test
  void I_saving_a_reservation_again_replaces_its_record() {
    UUID tenant = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      records().saveAll(tenant, departure.id(), List.of(delivered(RESERVATION_1, 4, null), delivered(RESERVATION_2, 2, null)), RECORDER, NOW);
      return null;
    });
    ServiceRecord noShow = new ServiceRecord(RESERVATION_1, ServiceResult.NOT_DELIVERED, 0, "did not come, no notice", true);
    transaction.run(() -> {
      records().saveAll(tenant, departure.id(), List.of(noShow), RECORDER, NOW.plusSeconds(60));
      return null;
    });
    assertThat(records().recordedFor(tenant, departure.id())).containsExactlyInAnyOrder(noShow, delivered(RESERVATION_2, 2, null));
  }

  @Test
  void I_another_tenant_sees_no_records_and_cannot_write_on_the_departure() {
    UUID tenant = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      records().saveAll(tenant, departure.id(), List.of(delivered(RESERVATION_1, 4, null)), RECORDER, NOW);
      return null;
    });
    assertThat(records().recordedFor(stranger, departure.id())).isEmpty();
    assertThatThrownBy(() -> transaction.run(() -> {
      records().saveAll(stranger, departure.id(), List.of(delivered(RESERVATION_2, 1, "x")), RECORDER, NOW);
      return null;
    })).isInstanceOf(Exception.class);
  }

  @Test
  void I_another_tenant_cannot_overwrite_the_record_of_the_same_reservation() {
    UUID tenant = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Departure departure = running(tenant);
    transaction.run(() -> {
      records().saveAll(tenant, departure.id(), List.of(delivered(RESERVATION_1, 4, null)), RECORDER, NOW);
      return null;
    });
    assertThatThrownBy(() -> transaction.run(() -> {
      records().saveAll(stranger, departure.id(), List.of(delivered(RESERVATION_1, 1, "x")), RECORDER, NOW);
      return null;
    })).isInstanceOf(IllegalStateException.class);
    assertThat(records().recordedFor(tenant, departure.id())).containsExactly(delivered(RESERVATION_1, 4, null));
  }
}
