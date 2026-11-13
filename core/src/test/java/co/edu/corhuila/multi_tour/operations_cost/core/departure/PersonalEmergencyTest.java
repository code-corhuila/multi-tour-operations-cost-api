package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-OPS-008 (api spec 011), RN-OPS-API-90, -91 and -95: the personal emergency of a reservation and the state that admits it. */
class PersonalEmergencyTest {

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
  void D_detail_and_affected_are_required_and_bounded() {
    assertThatThrownBy(() -> new PersonalEmergency(RESERVATION, 1, null, ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new PersonalEmergency(RESERVATION, 1, "   ", ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new PersonalEmergency(RESERVATION, 1, "d".repeat(1001), ACCOUNT, NOW))
        .isInstanceOf(InvalidDataException.class)
        .hasMessageContaining("1000");
    assertThatCode(() -> new PersonalEmergency(RESERVATION, 1, "d".repeat(1000), ACCOUNT, NOW)).doesNotThrowAnyException();
    assertThatThrownBy(() -> new PersonalEmergency(RESERVATION, 0, "a traveler fainted", ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new PersonalEmergency(RESERVATION, -2, "a traveler fainted", ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new PersonalEmergency(null, 1, "a traveler fainted", ACCOUNT, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new PersonalEmergency(RESERVATION, 1, "a traveler fainted", null, NOW)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new PersonalEmergency(RESERVATION, 1, "a traveler fainted", ACCOUNT, null)).isInstanceOf(InvalidDataException.class);
  }

  @Test
  void D_the_record_keeps_who_when_and_how_many() {
    PersonalEmergency emergency = new PersonalEmergency(RESERVATION, 2, " a traveler fainted on the trail ", ACCOUNT, NOW);
    assertThat(emergency.reservationId()).isEqualTo(RESERVATION);
    assertThat(emergency.affected()).isEqualTo(2);
    assertThat(emergency.detail()).isEqualTo("a traveler fainted on the trail");
    assertThat(emergency.recordedBy()).isEqualTo(ACCOUNT);
    assertThat(emergency.recordedAt()).isEqualTo(NOW);
  }

  @Test
  void D_personal_emergency_only_while_in_execution() {
    Departure running = scheduled().start(START.plus(Duration.ofMinutes(5)), ACCOUNT, NOW);
    assertThatCode(running::acceptPersonalEmergency).doesNotThrowAnyException();
    assertThatThrownBy(scheduled()::acceptPersonalEmergency).isInstanceOf(InvalidStatusTransitionException.class);
    assertThatThrownBy(running.finish(NOW, ACCOUNT, NOW, null)::acceptPersonalEmergency).isInstanceOf(InvalidStatusTransitionException.class);
  }
}
