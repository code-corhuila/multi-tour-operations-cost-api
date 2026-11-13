package co.edu.corhuila.multi_tour.operations_cost.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * The gate of {@code qa} (ADR-004 D7): it runs after every other test class and fails if an operation of the
 * contract was never exercised. Run on its own, it fails, on purpose: a contract nobody asked proves nothing.
 */
@Order(Integer.MAX_VALUE)
class ContractGateTest {

  @Test
  void C_every_operation_of_the_contract_was_exercised_by_a_test() {
    assertThat(Contract.operations()).isNotEmpty();
    assertThat(Contract.unexercised()).as("operations of openapi/openapi.yaml that no test asked").isEmpty();
  }
}
