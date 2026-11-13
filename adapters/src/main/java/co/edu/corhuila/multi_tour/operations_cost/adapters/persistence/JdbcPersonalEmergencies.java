package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.PersonalEmergencies;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.PersonalEmergency;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The personal emergencies of the reservations of a departure, in the schema operations_cost as the user operations_cost_app sees it
 * (HU-OPS-008, api 011). The tenant travels in every statement and in the key to the departure, so an emergency can never sit under the
 * departure of another tenant. An emergency is inserted once and never updated (the application user cannot): the reservation is kept as
 * its id only, it belongs to commercial-reservation.
 */
public final class JdbcPersonalEmergencies implements PersonalEmergencies {

  private static final RowMapper<PersonalEmergency> MAPPER = (rs, row) -> new PersonalEmergency(
      rs.getObject("reservation_id", UUID.class),
      rs.getInt("affected_travelers"),
      rs.getString("detail"),
      rs.getObject("recorded_by", UUID.class),
      rs.getObject("recorded_at", OffsetDateTime.class).toInstant());

  private final JdbcClient jdbc;

  public JdbcPersonalEmergencies(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void add(UUID tenantId, UUID departureId, PersonalEmergency emergency) {
    try {
      jdbc.sql("""
              INSERT INTO salida_personal_emergency (tenant_id, salida_id, reservation_id, affected_travelers, detail, recorded_by, recorded_at)
              VALUES (:tenant, :departure, :reservation, :affected, :detail, :by, :at)
              """)
          .param("tenant", tenantId)
          .param("departure", departureId)
          .param("reservation", emergency.reservationId())
          .param("affected", emergency.affected())
          .param("detail", emergency.detail())
          .param("by", emergency.recordedBy())
          .param("at", OffsetDateTime.ofInstant(emergency.recordedAt(), ZoneOffset.UTC))
          .update();
    } catch (org.springframework.dao.DuplicateKeyException already) {
      throw new DuplicateKeyException();
    }
  }

  @Override
  public boolean hasEmergency(UUID tenantId, UUID departureId, UUID reservationId) {
    return jdbc.sql("SELECT count(*) FROM salida_personal_emergency WHERE tenant_id = :tenant AND salida_id = :departure AND reservation_id = :reservation")
        .param("tenant", tenantId)
        .param("departure", departureId)
        .param("reservation", reservationId)
        .query(Long.class)
        .single() > 0;
  }

  @Override
  public List<PersonalEmergency> emergenciesOf(UUID tenantId, UUID departureId) {
    return jdbc.sql("""
            SELECT reservation_id, affected_travelers, detail, recorded_by, recorded_at FROM salida_personal_emergency
            WHERE tenant_id = :tenant AND salida_id = :departure ORDER BY recorded_at, reservation_id
            """)
        .param("tenant", tenantId)
        .param("departure", departureId)
        .query(MAPPER)
        .list();
  }
}
