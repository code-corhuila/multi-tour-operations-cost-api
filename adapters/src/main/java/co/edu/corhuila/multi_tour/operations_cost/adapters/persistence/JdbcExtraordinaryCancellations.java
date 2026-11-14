package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ExtraordinaryCancellation;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ExtraordinaryCancellations;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The extraordinary cancellations of the reservations of a departure, in the schema operations_cost as the user operations_cost_app sees it
 * (HU-OPS-005, api 009). The tenant travels in every statement and in the key to the departure, so a cancellation can never sit under the
 * departure of another tenant. A cancellation is inserted once and never updated (the application user cannot): the reservation is kept as
 * its id only, it belongs to commercial-reservation.
 */
public final class JdbcExtraordinaryCancellations implements ExtraordinaryCancellations {

  private static final RowMapper<ExtraordinaryCancellation> MAPPER = (rs, row) -> new ExtraordinaryCancellation(
      rs.getObject("reservation_id", UUID.class),
      rs.getString("justification"),
      rs.getObject("recorded_by", UUID.class),
      rs.getObject("recorded_at", OffsetDateTime.class).toInstant());

  private final JdbcClient jdbc;

  public JdbcExtraordinaryCancellations(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void add(UUID tenantId, UUID departureId, ExtraordinaryCancellation cancellation) {
    try {
      jdbc.sql("""
              INSERT INTO salida_extraordinary_cancellation (tenant_id, salida_id, reservation_id, justification, recorded_by, recorded_at)
              VALUES (:tenant, :departure, :reservation, :justification, :by, :at)
              """)
          .param("tenant", tenantId)
          .param("departure", departureId)
          .param("reservation", cancellation.reservationId())
          .param("justification", cancellation.justification())
          .param("by", cancellation.recordedBy())
          .param("at", OffsetDateTime.ofInstant(cancellation.recordedAt(), ZoneOffset.UTC))
          .update();
    } catch (org.springframework.dao.DuplicateKeyException already) {
      throw new DuplicateKeyException();
    }
  }

  @Override
  public boolean exists(UUID tenantId, UUID departureId, UUID reservationId) {
    return jdbc.sql("SELECT count(*) FROM salida_extraordinary_cancellation WHERE tenant_id = :tenant AND salida_id = :departure AND reservation_id = :reservation")
        .param("tenant", tenantId)
        .param("departure", departureId)
        .param("reservation", reservationId)
        .query(Long.class)
        .single() > 0;
  }

  @Override
  public List<ExtraordinaryCancellation> cancellationsOf(UUID tenantId, UUID departureId) {
    return jdbc.sql("""
            SELECT reservation_id, justification, recorded_by, recorded_at FROM salida_extraordinary_cancellation
            WHERE tenant_id = :tenant AND salida_id = :departure ORDER BY recorded_at, reservation_id
            """)
        .param("tenant", tenantId)
        .param("departure", departureId)
        .query(MAPPER)
        .list();
  }
}
