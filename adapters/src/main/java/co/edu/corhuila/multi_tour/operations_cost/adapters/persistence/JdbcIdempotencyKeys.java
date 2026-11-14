package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.DuplicateKeyException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.IdempotencyKeys;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The keys of the requests already made (a creation, a start, an end). The table has no tenant column, so the scope carries it: a key is good for one
 * operation of one tenant, and the same text in another tenant is another key.
 */
public final class JdbcIdempotencyKeys implements IdempotencyKeys {

  private final JdbcClient jdbc;

  public JdbcIdempotencyKeys(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<UUID> departureOf(UUID tenantId, String operation, String key) {
    return jdbc.sql("SELECT salida_id FROM idempotency_key WHERE scope = :scope AND idempotency_key = :key")
        .param("scope", scope(operation, tenantId))
        .param("key", key)
        .query(UUID.class)
        .optional();
  }

  @Override
  public void register(UUID tenantId, String operation, String key, UUID departureId) {
    try {
      jdbc.sql("INSERT INTO idempotency_key (scope, idempotency_key, salida_id) VALUES (:scope, :key, :departure)")
          .param("scope", scope(operation, tenantId))
          .param("key", key)
          .param("departure", departureId)
          .update();
    } catch (org.springframework.dao.DuplicateKeyException another) {
      throw new DuplicateKeyException();
    }
  }

  private static String scope(String operation, UUID tenantId) {
    return operation + "/" + tenantId;
  }
}
