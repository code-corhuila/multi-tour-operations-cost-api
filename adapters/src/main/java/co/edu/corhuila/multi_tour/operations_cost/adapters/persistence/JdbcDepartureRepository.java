package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Cancellation;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Departure;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureExecution;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureRepository;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureStatus;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
            UPDATE salida SET starts_at = :start, estimated_end_at = :end, pending_regularization = :pending
             WHERE tenant_id = :tenant AND salida_id = :id AND status = 'SCHEDULED'
               AND starts_at = :oldStart AND estimated_end_at = :oldEnd
            """)
        .param("start", utc(to.start()))
        .param("end", utc(to.estimatedEnd()))
        .param("pending", to.execution().pendingRegularization())
        .param("oldStart", utc(from.start()))
        .param("oldEnd", utc(from.estimatedEnd()))
        .param("tenant", from.tenantId())
        .param("id", from.id())
        .update() == 1;
  }

  /** The state that was read is the condition: a start, an end or a cancellation decided on a stale reading writes nothing. */
  @Override
  public boolean updateExecution(Departure from, Departure to) {
    DepartureExecution run = to.execution();
    Cancellation cancellation = run.cancellation();
    return jdbc.sql("""
            UPDATE salida SET status = :status, actual_start_at = :actualStart, started_by = :startedBy, actual_end_at = :actualEnd,
                              finished_by = :finishedBy, interrupted = :interrupted, interruption_cause = :cause,
                              pending_regularization = :pending, pending_marked_at = :markedAt, regularized_by = :regularizedBy,
                              regularized_at = :regularizedAt, early_start_reason = :earlyReason, cancelled_at = :cancelledAt,
                              cancelled_by = :cancelledBy, cancellation_cause = :cancellationCause, cancellation_reason = :cancellationReason
             WHERE tenant_id = :tenant AND salida_id = :id AND status = :oldStatus
            """)
        .param("status", to.status().name())
        .param("actualStart", run.actualStart() == null ? null : utc(run.actualStart()))
        .param("startedBy", run.startedBy())
        .param("actualEnd", run.actualEnd() == null ? null : utc(run.actualEnd()))
        .param("finishedBy", run.finishedBy())
        .param("interrupted", run.interrupted())
        .param("cause", run.interruptionCause())
        .param("pending", run.pendingRegularization())
        .param("markedAt", run.pendingMarkedAt() == null ? null : utc(run.pendingMarkedAt()))
        .param("regularizedBy", run.regularizedBy())
        .param("regularizedAt", run.regularizedAt() == null ? null : utc(run.regularizedAt()))
        .param("earlyReason", run.earlyStartReason())
        .param("cancelledAt", cancellation == null ? null : utc(cancellation.at()))
        .param("cancelledBy", cancellation == null ? null : cancellation.by())
        .param("cancellationCause", cancellation == null ? null : cancellation.cause().name())
        .param("cancellationReason", cancellation == null ? null : cancellation.reason())
        .param("tenant", from.tenantId())
        .param("id", from.id())
        .param("oldStatus", from.status().name())
        .update() == 1;
  }

  /** The rows stay locked until the transaction ends; SKIP LOCKED leaves to another ask the ones this one holds (api 005 D-03). */
  @Override
  public List<Departure> lockOpenPastEnd(Instant now, int limit) {
    return jdbc.sql(SELECT + """
             WHERE status IN ('SCHEDULED', 'IN_EXECUTION') AND NOT pending_regularization AND estimated_end_at <= :now
             ORDER BY estimated_end_at, salida_id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """)
        .param("now", utc(now))
        .param("limit", limit)
        .query(DepartureRow.MAPPER)
        .list();
  }

  @Override
  public Page<Departure> page(UUID tenantId, Optional<DepartureStatus> status, int page, int limit) {
    String filter = status.isPresent() ? " AND status = :status" : "";
    var count = jdbc.sql("SELECT count(*) FROM salida WHERE tenant_id = :tenant" + filter).param("tenant", tenantId);
    var rows = jdbc.sql(SELECT + " WHERE tenant_id = :tenant" + filter + " ORDER BY starts_at DESC, code DESC LIMIT :limit OFFSET :offset")
        .param("tenant", tenantId)
        .param("limit", limit)
        .param("offset", (long) (page - 1) * limit);
    if (status.isPresent()) {
      count = count.param("status", status.get().name());
      rows = rows.param("status", status.get().name());
    }
    return new Page<>(rows.query(DepartureRow.MAPPER).list(), page, limit, count.query(Long.class).single());
  }

  private static final String SELECT = """
      SELECT salida_id, tenant_id, code, service_id, starts_at, estimated_end_at, time_zone, status, scheduled_by,
             scheduled_at, actual_start_at, started_by, actual_end_at, finished_by, interrupted, interruption_cause,
             pending_regularization, pending_marked_at, regularized_by, regularized_at, early_start_reason,
             cancelled_at, cancelled_by, cancellation_cause, cancellation_reason
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
        rs.getObject("scheduled_at", OffsetDateTime.class).toInstant(),
        new DepartureExecution(
            instant(rs.getObject("actual_start_at", OffsetDateTime.class)),
            rs.getObject("started_by", UUID.class),
            instant(rs.getObject("actual_end_at", OffsetDateTime.class)),
            rs.getObject("finished_by", UUID.class),
            rs.getBoolean("interrupted"),
            rs.getString("interruption_cause"),
            rs.getBoolean("pending_regularization"),
            instant(rs.getObject("pending_marked_at", OffsetDateTime.class)),
            rs.getObject("regularized_by", UUID.class),
            instant(rs.getObject("regularized_at", OffsetDateTime.class)),
            rs.getString("early_start_reason"),
            cancellation(rs)));
  }

  /** A cancelled departure always has its instant and cause (the database check keeps them together), so the reading never builds a half one. */
  private static Cancellation cancellation(java.sql.ResultSet rs) throws java.sql.SQLException {
    Instant at = instant(rs.getObject("cancelled_at", OffsetDateTime.class));
    if (at == null) {
      return null;
    }
    return new Cancellation(at, rs.getObject("cancelled_by", UUID.class), Cancellation.Cause.valueOf(rs.getString("cancellation_cause")),
        rs.getString("cancellation_reason"));
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
