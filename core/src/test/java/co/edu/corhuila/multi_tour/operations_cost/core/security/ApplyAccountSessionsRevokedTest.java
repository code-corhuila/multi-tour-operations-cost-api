package co.edu.corhuila.multi_tour.operations_cost.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Transaction;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** HU-SEC-005 (api spec 013), RN-OPS-API-130 and AC-API-13.2: applying a revocation, ports as fakes. */
class ApplyAccountSessionsRevokedTest {

  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final Instant FIRST = Instant.parse("2026-11-14T12:00:00Z");
  private static final Instant LATER = FIRST.plusSeconds(60);
  private static final Instant EARLIER = FIRST.minusSeconds(60);

  /** The cutoffs and the processed ids in memory, with a transaction that puts both back when it fails. */
  private static final class Fakes implements AccountSessionCutoffs, ProcessedEvents, Transaction {
    final Map<UUID, AccountSessionCutoff> cutoffs = new HashMap<>();
    final Set<UUID> processed = new HashSet<>();
    boolean failAfterWork;

    @Override
    public Optional<AccountSessionCutoff> find(UUID accountId) {
      return Optional.ofNullable(cutoffs.get(accountId));
    }

    @Override
    public boolean keepLater(AccountSessionCutoff cutoff) {
      AccountSessionCutoff held = cutoffs.get(cutoff.accountId());
      if (held != null && !cutoff.revokedFrom().isAfter(held.revokedFrom())) {
        return false;
      }
      cutoffs.put(cutoff.accountId(), cutoff);
      return true;
    }

    @Override
    public boolean markIfNew(UUID eventId) {
      return processed.add(eventId);
    }

    @Override
    public <T> T run(Supplier<T> work) {
      Map<UUID, AccountSessionCutoff> cutoffsBefore = new HashMap<>(cutoffs);
      Set<UUID> processedBefore = new HashSet<>(processed);
      try {
        T result = work.get();
        if (failAfterWork) {
          throw new IllegalStateException("the commit failed");
        }
        return result;
      } catch (RuntimeException e) {
        cutoffs.clear();
        cutoffs.putAll(cutoffsBefore);
        processed.clear();
        processed.addAll(processedBefore);
        throw e;
      }
    }
  }

  private final Fakes fakes = new Fakes();
  private final ApplyAccountSessionsRevoked apply = new ApplyAccountSessionsRevoked(fakes, fakes, fakes);

  private static AccountSessionsRevoked event(UUID id, Instant from) {
    return new AccountSessionsRevoked(id, ACCOUNT, TENANT, from);
  }

  @Test
  void A_the_cutoff_never_moves_back_and_a_repeat_changes_nothing() {
    UUID first = UUID.randomUUID();
    assertThat(apply.execute(event(first, FIRST))).isEqualTo(ApplyAccountSessionsRevoked.Outcome.APPLIED);
    assertThat(fakes.find(ACCOUNT)).contains(new AccountSessionCutoff(ACCOUNT, TENANT, FIRST));

    assertThat(apply.execute(event(first, LATER))).isEqualTo(ApplyAccountSessionsRevoked.Outcome.ALREADY_PROCESSED);
    assertThat(fakes.find(ACCOUNT).orElseThrow().revokedFrom()).isEqualTo(FIRST);

    UUID older = UUID.randomUUID();
    assertThat(apply.execute(event(older, EARLIER))).isEqualTo(ApplyAccountSessionsRevoked.Outcome.UNCHANGED);
    assertThat(apply.execute(event(UUID.randomUUID(), FIRST))).isEqualTo(ApplyAccountSessionsRevoked.Outcome.UNCHANGED);
    assertThat(fakes.find(ACCOUNT).orElseThrow().revokedFrom()).isEqualTo(FIRST);
    // an older instant is still marked, so the relay stops delivering it
    assertThat(fakes.processed).contains(older);

    assertThat(apply.execute(event(UUID.randomUUID(), LATER))).isEqualTo(ApplyAccountSessionsRevoked.Outcome.APPLIED);
    assertThat(fakes.find(ACCOUNT).orElseThrow().revokedFrom()).isEqualTo(LATER);
  }

  @Test
  void A_the_cutoff_and_the_processed_id_commit_or_roll_back_together() {
    fakes.failAfterWork = true;
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> apply.execute(event(id, FIRST))).isInstanceOf(IllegalStateException.class);

    assertThat(fakes.cutoffs).isEmpty();
    assertThat(fakes.processed).isEmpty();
  }

  @Test
  void A_the_account_of_the_platform_has_no_tenant() {
    assertThat(apply.execute(new AccountSessionsRevoked(UUID.randomUUID(), ACCOUNT, null, FIRST)))
        .isEqualTo(ApplyAccountSessionsRevoked.Outcome.APPLIED);
    assertThat(fakes.find(ACCOUNT).orElseThrow().tenantId()).isNull();
  }

  @Test
  void A_a_fact_without_its_event_account_or_instant_is_refused() {
    assertThatThrownBy(() -> new AccountSessionsRevoked(null, ACCOUNT, TENANT, FIRST)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccountSessionsRevoked(UUID.randomUUID(), null, TENANT, FIRST)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccountSessionsRevoked(UUID.randomUUID(), ACCOUNT, TENANT, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccountSessionCutoff(null, TENANT, FIRST)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccountSessionCutoff(ACCOUNT, TENANT, null)).isInstanceOf(IllegalArgumentException.class);
  }
}
