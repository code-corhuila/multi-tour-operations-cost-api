package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServiceRecord;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServiceRecords;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ServiceResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What each reservation of a departure received, in the schema operations_cost as the user operations_cost_app sees it. The tenant travels
 * in every statement and in the key to the departure, so a record can never sit under the departure of another tenant. Saving a reservation
 * again replaces its record; the reservation is kept as its id only, its travelers stay with commercial-reservation.
 */
public final class JdbcServiceRecords implements ServiceRecords {

  private static final RowMapper<ServiceRecord> MAPPER = (rs, row) -> new ServiceRecord(
      rs.getObject("reservation_id", UUID.class),
      ServiceResult.valueOf(rs.getString("result")),
      rs.getInt("travelers_served"),
      rs.getString("cause"),
      rs.getBoolean("no_show"));

  private final JdbcClient jdbc;

  public JdbcServiceRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void saveAll(UUID tenantId, UUID departureId, Collection<ServiceRecord> records, UUID by, Instant at) {
    for (ServiceRecord record : records) {
      int written = jdbc.sql("""
              INSERT INTO salida_service (tenant_id, salida_id, reservation_id, result, travelers_served, cause, no_show, recorded_by, recorded_at)
              VALUES (:tenant, :departure, :reservation, :result, :served, :cause, :noShow, :by, :at)
              ON CONFLICT (salida_id, reservation_id) DO UPDATE SET
                result = EXCLUDED.result, travelers_served = EXCLUDED.travelers_served, cause = EXCLUDED.cause,
                no_show = EXCLUDED.no_show, recorded_by = EXCLUDED.recorded_by, recorded_at = EXCLUDED.recorded_at
              WHERE salida_service.tenant_id = EXCLUDED.tenant_id
              """)
          .param("tenant", tenantId)
          .param("departure", departureId)
          .param("reservation", record.reservationId())
          .param("result", record.result().name())
          .param("served", record.travelersServed())
          .param("cause", record.cause())
          .param("noShow", record.noShow())
          .param("by", by)
          .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
          .update();
      // the guard of the upsert leaves a row of another tenant untouched: that must not look like a save
      if (written != 1) {
        throw new IllegalStateException("The record of the reservation " + record.reservationId() + " was not written");
      }
    }
  }

  @Override
  public List<ServiceRecord> recordedFor(UUID tenantId, UUID departureId) {
    return jdbc.sql("""
            SELECT reservation_id, result, travelers_served, cause, no_show FROM salida_service
            WHERE tenant_id = :tenant AND salida_id = :departure ORDER BY recorded_at, reservation_id
            """)
        .param("tenant", tenantId)
        .param("departure", departureId)
        .query(MAPPER)
        .list();
  }
}
