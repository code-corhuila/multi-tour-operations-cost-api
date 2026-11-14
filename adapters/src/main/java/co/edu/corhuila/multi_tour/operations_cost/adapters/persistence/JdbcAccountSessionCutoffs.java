package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.security.AccountSessionCutoff;
import co.edu.corhuila.multi_tour.operations_cost.core.security.AccountSessionCutoffs;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The cutoff of each account (RN-OPS-API-130). One statement keeps "never moves back" when two revocations arrive at once. The instant is
 * cut to microseconds, the precision of the column, so what is compared is what is stored.
 */
public final class JdbcAccountSessionCutoffs implements AccountSessionCutoffs {

  private final JdbcClient jdbc;

  public JdbcAccountSessionCutoffs(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AccountSessionCutoff> find(UUID accountId) {
    return jdbc.sql("SELECT account_id, tenant_id, revoked_from FROM account_session_cutoff WHERE account_id = :account")
        .param("account", accountId)
        .query((rs, row) -> new AccountSessionCutoff(rs.getObject("account_id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getObject("revoked_from", OffsetDateTime.class).toInstant()))
        .optional();
  }

  @Override
  public boolean keepLater(AccountSessionCutoff cutoff) {
    Instant from = cutoff.revokedFrom().truncatedTo(ChronoUnit.MICROS);
    return jdbc.sql("""
            INSERT INTO account_session_cutoff (account_id, tenant_id, revoked_from) VALUES (:account, :tenant, :from)
            ON CONFLICT (account_id) DO UPDATE SET revoked_from = excluded.revoked_from, tenant_id = COALESCE(excluded.tenant_id, account_session_cutoff.tenant_id), updated_at = now()
            WHERE account_session_cutoff.revoked_from < excluded.revoked_from""")
        .param("account", cutoff.accountId())
        .param("tenant", cutoff.tenantId())
        .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
        .update() == 1;
  }
}
