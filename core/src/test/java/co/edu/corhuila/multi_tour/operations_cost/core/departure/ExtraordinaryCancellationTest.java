package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-OPS-005 (api spec 009), RN-OPS-API-70 and -71: the justification of the cancellation and the state of the departure. */
class ExtraordinaryCancellationTest {

  private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
  private static final Instant START = NOW.minus(Duration.ofHours(4));
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final UUID RESERVATION = new UUID(0, 0x101);

  private static Departure scheduled() {
    return new Departure(new UUID(0, 1001), TENANT, "SAL-001", TOUR, START, START.plus(Duration.ofHours(8)), "America/Bogota",
        DepartureStatus.SCHEDULED, ACCOUNT, START.minus(Duration.ofDays(2)));
  }

  @Test
  void D_justification_is_required_and_bounded() {
    assertThatThrownBy(() -> new ExtraordinaryCancellation(RESERVATION, null, ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new ExtraordinaryCancellation(RESERVATION, "   ", ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new ExtraordinaryCancellation(RESERVATION, "j".repeat(501), ACCOUNT, NOW))
        .isInstanceOf(InvalidDataException.class)
        .hasMessageContaining("500");
    assertThatCode(() -> new ExtraordinaryCancellation(RESERVATION, "j".repeat(500), ACCOUNT, NOW)).doesNotThrowAnyException();
    assertThat(new ExtraordinaryCancellation(RESERVATION, "a family emergency", ACCOUNT, NOW).justification()).isEqualTo("a family emergency");
  }

  @Test
  void D_extraordinary_cancellation_only_while_in_execution() {
    Departure running = scheduled().start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    assertThatCode(running::acceptExtraordinaryCancellation).doesNotThrowAnyException();
    assertThatThrownBy(scheduled()::acceptExtraordinaryCancellation).isInstanceOf(InvalidStatusTransitionException.class);
    assertThatThrownBy(running.finish(NOW, ACCOUNT, NOW, null)::acceptExtraordinaryCancellation).isInstanceOf(InvalidStatusTransitionException.class);
  }
}
