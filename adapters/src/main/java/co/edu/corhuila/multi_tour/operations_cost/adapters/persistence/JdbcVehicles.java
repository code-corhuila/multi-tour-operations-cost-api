package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.BusinessRuleViolationException;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicles;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The vehicles of the schema operations_cost and their assignment to departures, as the user operations_cost_app sees it. Every
 * read filters by tenant. The vehicles of a request are locked in id order until the end of the transaction (plan D-05), so two
 * requests that want the same vehicles never wait on each other, and the overlap is judged while nobody else can assign them.
 */
public final class JdbcVehicles implements Vehicles {

  private static final RowMapper<Vehicle> MAPPER = (rs, row) -> new Vehicle(
      rs.getObject("vehicle_id", UUID.class),
      rs.getObject("tenant_id", UUID.class),
      rs.getString("name"),
      rs.getInt("capacity"),
      rs.getObject("created_by", UUID.class),
      rs.getObject("created_at", OffsetDateTime.class).toInstant());

  private static final String COLUMNS = "v.vehicle_id, v.tenant_id, v.name, v.capacity, v.created_by, v.created_at";

  private final JdbcClient jdbc;

  public JdbcVehicles(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(Vehicle vehicle) {
    try {
      jdbc.sql("""
              INSERT INTO vehicle (vehicle_id, tenant_id, name, capacity, created_by, created_at)
              VALUES (:id, :tenant, :name, :capacity, :by, :at)
              """)
          .param("id", vehicle.id())
          .param("tenant", vehicle.tenantId())
          .param("name", vehicle.name())
          .param("capacity", vehicle.capacity())
          .param("by", vehicle.createdBy())
          .param("at", utc(vehicle.createdAt()))
          .update();
    } catch (DuplicateKeyException sameName) {
      // two requests registered the same name at once: the unique index of the database decided
      throw new BusinessRuleViolationException("A vehicle with that name already exists");
    }
  }

  @Override
  public boolean nameTaken(UUID tenantId, String name) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM vehicle WHERE tenant_id = :tenant AND lower(btrim(name)) = lower(btrim(:name)))")
        .param("tenant", tenantId)
        .param("name", name)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Page<Vehicle> list(UUID tenantId, int page, int limit) {
    long total = jdbc.sql("SELECT count(*) FROM vehicle WHERE tenant_id = :tenant").param("tenant", tenantId).query(Long.class).single();
    List<Vehicle> data = jdbc.sql("SELECT " + COLUMNS + " FROM vehicle v WHERE v.tenant_id = :tenant ORDER BY lower(v.name), v.vehicle_id LIMIT :limit OFFSET :offset")
        .param("tenant", tenantId)
        .param("limit", limit)
        .param("offset", (long) (page - 1) * limit)
        .query(MAPPER)
        .list();
    return new Page<>(data, page, limit, total);
  }

  @Override
  public List<Vehicle> lockAll(UUID tenantId, Collection<UUID> vehicleIds) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM vehicle v WHERE v.tenant_id = :tenant AND v.vehicle_id IN (:ids) ORDER BY v.vehicle_id FOR UPDATE")
        .param("tenant", tenantId)
        .param("ids", vehicleIds)
        .query(MAPPER)
        .list();
  }

  @Override
  public List<Vehicle> ofDeparture(UUID tenantId, UUID departureId) {
    return jdbc.sql("""
            SELECT %s FROM vehicle v
              JOIN salida_vehicle sv ON sv.tenant_id = v.tenant_id AND sv.vehicle_id = v.vehicle_id
             WHERE sv.tenant_id = :tenant AND sv.salida_id = :departure
             ORDER BY lower(v.name), v.vehicle_id
            """.formatted(COLUMNS))
        .param("tenant", tenantId)
        .param("departure", departureId)
        .query(MAPPER)
        .list();
  }

  @Override
  public Optional<String> overlapping(UUID tenantId, Collection<UUID> vehicleIds, Instant start, Instant end, UUID except) {
    return jdbc.sql("""
            SELECT s.code FROM salida s
              JOIN salida_vehicle sv ON sv.tenant_id = s.tenant_id AND sv.salida_id = s.salida_id
             WHERE s.tenant_id = :tenant AND sv.vehicle_id IN (:ids) AND s.salida_id <> :except
               AND s.status IN ('SCHEDULED', 'IN_EXECUTION')
               AND s.starts_at < :end AND :start < s.estimated_end_at
             ORDER BY s.starts_at, s.code
             LIMIT 1
            """)
        .param("tenant", tenantId)
        .param("ids", vehicleIds)
        .param("except", except)
        .param("start", utc(start))
        .param("end", utc(end))
        .query(String.class)
        .optional();
  }

  @Override
  public void assign(UUID tenantId, UUID departureId, Collection<UUID> vehicleIds, UUID assignedBy, Instant assignedAt) {
    for (UUID vehicleId : vehicleIds) {
      jdbc.sql("""
              INSERT INTO salida_vehicle (tenant_id, salida_id, vehicle_id, assigned_by, assigned_at)
              VALUES (:tenant, :departure, :vehicle, :by, :at)
              ON CONFLICT (salida_id, vehicle_id) DO NOTHING
              """)
          .param("tenant", tenantId)
          .param("departure", departureId)
          .param("vehicle", vehicleId)
          .param("by", assignedBy)
          .param("at", utc(assignedAt))
          .update();
    }
  }

  @Override
  public void unassign(UUID tenantId, UUID departureId, Collection<UUID> vehicleIds) {
    if (vehicleIds.isEmpty()) {
      return;
    }
    jdbc.sql("DELETE FROM salida_vehicle WHERE tenant_id = :tenant AND salida_id = :departure AND vehicle_id IN (:ids)")
        .param("tenant", tenantId)
        .param("departure", departureId)
        .param("ids", vehicleIds)
        .update();
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
