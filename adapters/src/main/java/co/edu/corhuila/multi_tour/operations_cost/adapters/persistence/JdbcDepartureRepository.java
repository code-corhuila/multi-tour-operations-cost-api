package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureRepository;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The departures of the schema operations_cost, as the user operations_cost_app sees it (its search_path is the schema,
 * Annex J.4). Every read filters by tenant. The next code takes a lock of the tenant that lasts until the end of the
 * transaction, so two creations of one tenant never read the same last code (plan D-04).
 */
public final class JdbcDepartureRepository implements DepartureRepository {

  private final JdbcClient jdbc;

  public JdbcDepartureRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String nextCode(UUID tenantId) {
    jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:tenant, 0))) locked")
        .param("tenant", tenantId.toString())
        .query(Integer.class)
        .single();
    int next = jdbc.sql("SELECT coalesce(max(substring(code from 5)::int), 0) + 1 FROM salida WHERE tenant_id = :tenant")
        .param("tenant", tenantId)
        .query(Integer.class)
        .single();
    return String.format("SAL-%03d", next);
  }

  @Override
  public void save(Departure departure) {
    jdbc.sql("""
            INSERT INTO salida (salida_id, tenant_id, code, service_id, starts_at, estimated_end_at, time_zone, status,
                                scheduled_by, scheduled_at)
            VALUES (:id, :tenant, :code, :service, :start, :end, :zone, :status, :by, :at)
            """)
        .param("id", departure.id())
        .param("tenant", departure.tenantId())
        .param("code", departure.code())
        .param("service", departure.serviceId())
        .param("start", utc(departure.start()))
        .param("end", utc(departure.estimatedEnd()))
        .param("zone", departure.timeZone())
        .param("status", departure.status().name())
        .param("by", departure.scheduledBy())
        .param("at", utc(departure.scheduledAt()))
        .update();
  }

  @Override
  public Optional<Departure> find(UUID tenantId, UUID id) {
    return jdbc.sql(SELECT + " WHERE tenant_id = :tenant AND salida_id = :id")
        .param("tenant", tenantId)
        .param("id", id)
        .query(DepartureRow.MAPPER)
        .optional();
  }

  @Override
  public Optional<Departure> findForUpdate(UUID tenantId, UUID id) {
    return jdbc.sql(SELECT + " WHERE tenant_id = :tenant AND salida_id = :id FOR UPDATE")
        .param("tenant", tenantId)
        .param("id", id)
        .query(DepartureRow.MAPPER)
        .optional();
  }

  @Override
  public Optional<Departure> findByCode(UUID tenantId, String code) {
    return jdbc.sql(SELECT + " WHERE tenant_id = :tenant AND code = :code")
        .param("tenant", tenantId)
        .param("code", code)
        .query(DepartureRow.MAPPER)
        .optional();
  }

  /** The state and the dates that were read are the condition: a change decided on a stale reading writes nothing. */
  @Override
  public boolean updateSchedule(Departure from, Departure to) {
    return jdbc.sql("""
            UPDATE salida SET starts_at = :start, estimated_end_at = :end
             WHERE tenant_id = :tenant AND salida_id = :id AND status = 'SCHEDULED'
               AND starts_at = :oldStart AND estimated_end_at = :oldEnd
            """)
        .param("start", utc(to.start()))
        .param("end", utc(to.estimatedEnd()))
        .param("oldStart", utc(from.start()))
        .param("oldEnd", utc(from.estimatedEnd()))
        .param("tenant", from.tenantId())
        .param("id", from.id())
        .update() == 1;
  }

  private static final String SELECT = """
      SELECT salida_id, tenant_id, code, service_id, starts_at, estimated_end_at, time_zone, status, scheduled_by,
             scheduled_at
        FROM salida""";

  private static final class DepartureRow {
    static final org.springframework.jdbc.core.RowMapper<Departure> MAPPER = (rs, row) -> new Departure(
        rs.getObject("salida_id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("code"),
        rs.getObject("service_id", UUID.class),
        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
        rs.getObject("estimated_end_at", OffsetDateTime.class).toInstant(),
        rs.getString("time_zone"),
        DepartureStatus.valueOf(rs.getString("status")),
        rs.getObject("scheduled_by", UUID.class),
        rs.getObject("scheduled_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
