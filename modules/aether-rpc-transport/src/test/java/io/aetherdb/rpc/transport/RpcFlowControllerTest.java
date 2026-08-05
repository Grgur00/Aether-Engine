package io.aetherdb.rpc.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class RpcFlowControllerTest {
    @Test void creditCannotBeExceededOrOverflowed() {
        RpcFlowController flow = new RpcFlowController(1024 * 1024);
        assertThat(flow.tryReserve(900_000)).isTrue(); assertThat(flow.tryReserve(200_000)).isFalse();
        flow.update(200_000); assertThat(flow.tryReserve(200_000)).isTrue();
        RpcFlowController maximum = new RpcFlowController(RpcFlowController.MAXIMUM_WINDOW);
        assertThatThrownBy(() -> maximum.update(1)).isInstanceOf(IllegalArgumentException.class);
    }
}
