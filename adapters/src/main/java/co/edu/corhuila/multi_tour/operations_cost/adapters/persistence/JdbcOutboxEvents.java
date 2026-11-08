package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.outbox.OutboxEvents;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.RetryPolicy;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.StoredEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The outbox as job 7 reads and updates it. Each change is one statement with its condition, so reports that arrive at once are
 * all counted and none is lost: the event is read back after the change, and the read is the answer.
 */
public final class JdbcOutboxEvents implements OutboxEvents {

  private static final String SELECT = """
      SELECT outbox_event_id, event_type, event_version, tenant_id, aggregate_id, actor_id, reason, payload::text AS payload,
             correlation_id, occurred_at, published_at, status, attempts, last_error, next_attempt_at
        FROM outbox_event""";

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final RowMapper<StoredEvent> mapper;

  public JdbcOutboxEvents(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
    this.mapper = this::row;
  }

  @Override
  public Page<StoredEvent> due(Instant now, int page, int limit) {
    long total = jdbc.sql("SELECT count(*) FROM outbox_event WHERE status = 'PENDING' AND next_attempt_at <= :now")
        .param("now", utc(now)).query(Long.class).single();
    var data = jdbc.sql(SELECT + " WHERE status = 'PENDING' AND next_attempt_at <= :now ORDER BY occurred_at, outbox_event_id LIMIT :limit OFFSET :offset")
        .param("now", utc(now))
        .param("limit", limit)
        .param("offset", (long) (page - 1) * limit)
        .query(mapper)
        .list();
    return new Page<>(data, page, limit, total);
  }

  @Override
  public Optional<StoredEvent> markPublished(UUID eventId, Instant at) {
    jdbc.sql("UPDATE outbox_event SET status = 'PUBLISHED', published_at = :at WHERE outbox_event_id = :id AND status <> 'PUBLISHED'")
        .param("at", utc(at))
        .param("id", eventId)
        .update();
    return find(eventId);
  }

  @Override
  public Optional<StoredEvent> recordFailedAttempt(UUID eventId, String error, Instant at, RetryPolicy policy) {
    // the wait after the n-th attempt is base x 2^(n-1) up to the cap; here n - 1 is the count before this report
    jdbc.sql("""
            UPDATE outbox_event
               SET attempts = attempts + 1,
                   last_error = :error,
                   status = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE 'PENDING' END,
                   next_attempt_at = CAST(:at AS timestamptz)
                       + make_interval(secs => LEAST(CAST(:cap AS double precision), CAST(:base AS double precision) * power(2, attempts)))
             WHERE outbox_event_id = :id AND status = 'PENDING'
            """)
        .param("error", error)
        .param("max", policy.maxAttempts())
        .param("at", utc(at))
        .param("cap", (double) policy.cap().toSeconds())
        .param("base", (double) policy.base().toSeconds())
        .param("id", eventId)
        .update();
    return find(eventId);
  }

  private Optional<StoredEvent> find(UUID eventId) {
    return jdbc.sql(SELECT + " WHERE outbox_event_id = :id").param("id", eventId).query(mapper).optional();
  }

  private StoredEvent row(java.sql.ResultSet rs, int rowNumber) throws SQLException {
    Map<String, Object> payload;
    try {
      payload = json.readValue(rs.getString("payload"), new TypeReference<Map<String, Object>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new SQLException("The payload of an event is not a JSON object", e);
    }
    OffsetDateTime published = rs.getObject("published_at", OffsetDateTime.class);
    return new StoredEvent(
        rs.getObject("outbox_event_id", UUID.class),
        rs.getString("event_type"),
        rs.getInt("event_version"),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("aggregate_id", UUID.class),
        rs.getObject("actor_id", UUID.class),
        rs.getString("reason"),
        payload,
        rs.getString("correlation_id"),
        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
        published == null ? null : published.toInstant(),
        StoredEvent.Status.valueOf(rs.getString("status")),
        rs.getInt("attempts"),
        rs.getString("last_error"),
        rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
