package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CostMode;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OperationalCost;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.OperationalCosts;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The operational costs in the schema operations_cost, as the user operations_cost_app sees it: it inserts and reads, and the database
 * does not let it change a line. The tenant travels in every statement and in the key to the departure. The key of the creation is a column
 * of the line, so the line and its key are one row and a repeat finds it; the unique key decides the race of two equal keys. It joins the
 * transaction of the caller, so the line and its event are written together.
 */
public final class JdbcOperationalCosts implements OperationalCosts {

  private static final String COLUMNS = """
      cost_id, tenant_id, salida_id, concept, amount_cents, mode, unit_cost_cents, travelers_served, without_parametrization, justification,
      recorded_by, recorded_at""";

  private static final RowMapper<OperationalCost> MAPPER = (rs, row) -> new OperationalCost(
      rs.getObject("cost_id", UUID.class),
      rs.getObject("tenant_id", UUID.class),
      rs.getObject("salida_id", UUID.class),
      rs.getString("concept"),
      rs.getLong("amount_cents"),
      CostMode.valueOf(rs.getString("mode")),
      (Long) rs.getObject("unit_cost_cents"),
      (Integer) rs.getObject("travelers_served"),
      rs.getBoolean("without_parametrization"),
      rs.getString("justification"),
      rs.getObject("recorded_by", UUID.class),
      rs.getObject("recorded_at", OffsetDateTime.class).toInstant());

  private final JdbcClient jdbc;

  public JdbcOperationalCosts(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void add(OperationalCost cost, String idempotencyKey) {
    try {
      jdbc.sql("INSERT INTO salida_cost (" + COLUMNS + ", idempotency_key) VALUES (:id, :tenant, :departure, :concept, :amount, :mode, :unit, :served,"
              + " :without, :justification, :by, :at, :key)")
          .param("id", cost.costId())
          .param("tenant", cost.tenantId())
          .param("departure", cost.departureId())
          .param("concept", cost.concept())
          .param("amount", cost.amountCents())
          .param("mode", cost.mode().name())
          .param("unit", cost.unitCostCents())
          .param("served", cost.travelersServed())
          .param("without", cost.withoutParametrization())
          .param("justification", cost.justification())
          .param("by", cost.recordedBy())
          .param("at", OffsetDateTime.ofInstant(cost.recordedAt(), ZoneOffset.UTC))
          .param("key", idempotencyKey)
          .update();
    } catch (org.springframework.dao.DuplicateKeyException another) {
      throw new DuplicateKeyException();
    }
  }

  @Override
  public Optional<OperationalCost> findByKey(UUID tenantId, String idempotencyKey) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM salida_cost WHERE tenant_id = :tenant AND idempotency_key = :key")
        .param("tenant", tenantId)
        .param("key", idempotencyKey)
        .query(MAPPER)
        .optional();
  }

  @Override
  public List<OperationalCost> costsOf(UUID tenantId, UUID departureId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM salida_cost WHERE tenant_id = :tenant AND salida_id = :departure ORDER BY recorded_at, cost_id")
        .param("tenant", tenantId)
        .param("departure", departureId)
        .query(MAPPER)
        .list();
  }
}
