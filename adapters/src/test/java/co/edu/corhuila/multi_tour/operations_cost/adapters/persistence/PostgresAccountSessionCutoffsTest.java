package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.security.AccountSessionCutoff;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * HU-SEC-005 (api spec 013), RN-OPS-API-130: the cutoffs and the processed events over the real engine. The column keeps
 * microseconds, so the adapter compares instants at that precision: one microsecond later moves the cutoff, the same instant does not.
 */
class PostgresAccountSessionCutoffsTest extends PostgresTest {

  private static final Instant FIRST = Instant.parse("2026-11-14T12:00:00.123456Z");

  @Test
  void I_the_cutoff_and_the_processed_event_are_written_together() {
    JdbcAccountSessionCutoffs cutoffs = new JdbcAccountSessionCutoffs(jdbc);
    JdbcProcessedEvents processed = new JdbcProcessedEvents(jdbc);
    UUID account = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();

    assertThat(cutoffs.find(account)).isEmpty();
    assertThat(cutoffs.keepLater(new AccountSessionCutoff(account, tenant, FIRST))).as("insert").isTrue();
    assertThat(cutoffs.find(account)).contains(new AccountSessionCutoff(account, tenant, FIRST));

    assertThat(cutoffs.keepLater(new AccountSessionCutoff(account, tenant, FIRST))).as("the same instant").isFalse();
    assertThat(cutoffs.keepLater(new AccountSessionCutoff(account, tenant, FIRST.minusNanos(1_000)))).as("back").isFalse();
    Instant later = FIRST.plusNanos(1_000);
    assertThat(cutoffs.keepLater(new AccountSessionCutoff(account, tenant, later))).as("one microsecond later").isTrue();
    assertThat(cutoffs.find(account)).contains(new AccountSessionCutoff(account, tenant, later));
    Instant laterStill = later.plusSeconds(1);
    assertThat(cutoffs.keepLater(new AccountSessionCutoff(account, null, laterStill))).as("a later event without its tenant").isTrue();
    assertThat(cutoffs.find(account)).as("keeps the tenant it had").contains(new AccountSessionCutoff(account, tenant, laterStill));

    UUID platformAdmin = UUID.randomUUID();
    assertThat(cutoffs.keepLater(new AccountSessionCutoff(platformAdmin, null, FIRST))).isTrue();
    assertThat(cutoffs.find(platformAdmin)).contains(new AccountSessionCutoff(platformAdmin, null, FIRST));

    UUID event = UUID.randomUUID();
    assertThat(processed.markIfNew(event)).isTrue();
    assertThat(processed.markIfNew(event)).as("a repeat").isFalse();

    UUID failedEvent = UUID.randomUUID();
    UUID failedAccount = UUID.randomUUID();
    assertThatThrownBy(() -> transaction.run(() -> {
      processed.markIfNew(failedEvent);
      cutoffs.keepLater(new AccountSessionCutoff(failedAccount, tenant, FIRST));
      throw new IllegalStateException("the work failed");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(cutoffs.find(failedAccount)).as("no cutoff after the rollback").isEmpty();
    assertThat(processed.markIfNew(failedEvent)).as("no mark after the rollback").isTrue();
  }
}
