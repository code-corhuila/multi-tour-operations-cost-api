package co.edu.corhuila.multi_tour.operations_cost.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-SEC-005 (api spec 013), RN-OPS-API-131: a token whose iat, in whole seconds, is earlier than the cutoff no longer counts. */
class AccountSessionCutoffTest {

  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private final AccountSessionCutoff cutoff = new AccountSessionCutoff(ACCOUNT, null, Instant.parse("2026-11-14T12:00:00.400Z"));

  @Test
  void A_a_token_issued_before_the_cutoff_is_refused() {
    assertThat(cutoff.refuses(Instant.parse("2026-11-14T11:59:59Z"))).isTrue();
    assertThat(cutoff.refuses(Instant.parse("2026-11-14T11:59:59.999Z"))).isTrue();
  }

  @Test
  void A_a_token_issued_in_the_second_of_the_cutoff_is_a_new_session() {
    assertThat(cutoff.refuses(Instant.parse("2026-11-14T12:00:00Z"))).isFalse();
    assertThat(cutoff.refuses(Instant.parse("2026-11-14T12:00:00.100Z"))).isFalse();
  }

  @Test
  void A_a_token_issued_after_the_cutoff_passes() {
    assertThat(cutoff.refuses(Instant.parse("2026-11-14T12:00:01Z"))).isFalse();
  }

  @Test
  void A_a_token_with_no_iat_cannot_be_shown_to_be_later() {
    assertThat(cutoff.refuses(null)).isTrue();
  }
}
