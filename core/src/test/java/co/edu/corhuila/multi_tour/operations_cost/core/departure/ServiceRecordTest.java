package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What one reservation received, with no framework and no double (RN-OPS-API-61 to -63 and -65). */
class ServiceRecordTest {

  private static final UUID RESERVATION = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

  private static ServiceRecord delivered(int served, String cause) {
    return new ServiceRecord(RESERVATION, ServiceResult.DELIVERED, served, cause, false);
  }

  private static ServiceRecord notDelivered(String cause, boolean noShow) {
    return new ServiceRecord(RESERVATION, ServiceResult.NOT_DELIVERED, 0, cause, noShow);
  }

  @Test
  void D_served_is_within_the_reserved() {
    delivered(4, null).checkAgainst(4);
    delivered(3, "one traveler stayed").checkAgainst(4);
    assertThatThrownBy(() -> delivered(5, null).checkAgainst(4)).isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void D_zero_served_is_not_delivered() {
    assertThatThrownBy(() -> delivered(0, null)).isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("travelersServed");
    assertThatThrownBy(() -> delivered(-1, null)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> new ServiceRecord(RESERVATION, ServiceResult.NOT_DELIVERED, 1, "x", false))
        .isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("travelersServed");
  }

  @Test
  void D_missing_travelers_need_a_cause() {
    assertThatThrownBy(() -> delivered(3, null).checkAgainst(4)).isInstanceOf(InvalidDataException.class).extracting("field").isEqualTo("cause");
    assertThatThrownBy(() -> delivered(3, "  ").checkAgainst(4)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> notDelivered(null, false).checkAgainst(2)).isInstanceOf(InvalidDataException.class);
    assertThatThrownBy(() -> notDelivered("x".repeat(501), false)).isInstanceOf(InvalidDataException.class);
    delivered(4, null).checkAgainst(4);
    notDelivered("the group never arrived", false).checkAgainst(2);
  }

  @Test
  void D_no_show_is_not_delivered_and_charged() {
    ServiceRecord noShow = notDelivered("did not come, no notice", true);
    assertThat(noShow.noShow()).isTrue();
    assertThat(noShow.result()).isEqualTo(ServiceResult.NOT_DELIVERED);
    assertThatThrownBy(() -> new ServiceRecord(RESERVATION, ServiceResult.DELIVERED, 2, null, true)).isInstanceOf(InvalidDataException.class)
        .extracting("field").isEqualTo("noShow");
  }

  @Test
  void D_real_base_counts_only_the_delivered() {
    List<ServiceRecord> records = List.of(delivered(4, null), delivered(2, "one stayed"), notDelivered("no show", true));
    assertThat(ServiceRecord.realBase(records)).isEqualTo(6);
    assertThat(ServiceRecord.realBase(List.of())).isZero();
  }
}
