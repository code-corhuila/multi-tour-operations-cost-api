package co.edu.corhuila.multi_tour.operations_cost.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Permission;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CallerTest {

  @Test
  void D_a_tenant_administrator_carries_its_tenant() {
    Caller caller = new Caller("account-1", "tenant-1", Set.of(Role.TENANT_ADMINISTRATOR));
    assertThat(caller.tenantId()).isEqualTo("tenant-1");
  }

  @Test
  void D_a_caller_holds_the_permissions_of_its_token_and_none_by_default() {
    Caller without = new Caller("account-1", "tenant-1", Set.of(Role.TENANT_ADMINISTRATOR));
    assertThat(without.permissions()).isEmpty();
    Caller with = new Caller("account-1", "tenant-1", Set.of(Role.OPERATIONAL_COLLABORATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));
    assertThat(with.permissions()).containsExactly("MANAGE_DEPARTURE_EXECUTION");
    assertThat(with.holds(Permission.MANAGE_DEPARTURE_EXECUTION)).isTrue();
    assertThat(without.holds(Permission.MANAGE_DEPARTURE_EXECUTION)).isFalse();
  }

  @Test
  void D_a_role_of_a_tenant_without_the_tenant_is_not_a_caller() {
    assertThatThrownBy(() -> new Caller("account-1", null, Set.of(Role.TENANT_ADMINISTRATOR)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Caller("account-1", " ", Set.of(Role.MANAGER)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void D_the_platform_and_the_services_have_no_tenant() {
    assertThatThrownBy(() -> new Caller("admin", "tenant-1", Set.of(Role.PLATFORM_ADMINISTRATOR)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new Caller("event-relay", "", Set.of(Role.SERVICE)).tenantId()).isNull();
  }

  @Test
  void D_the_role_of_a_tenant_is_never_mixed_with_the_platform_or_a_service() {
    assertThatThrownBy(() -> new Caller("x", "tenant-1", Set.of(Role.SERVICE, Role.MANAGER)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void D_a_caller_needs_a_subject_and_at_least_one_role() {
    assertThatThrownBy(() -> new Caller(" ", null, Set.of(Role.SERVICE)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Caller("someone", null, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
