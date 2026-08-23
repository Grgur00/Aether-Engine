package io.aetherdb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherdb.client.api.ClientStatus;
import io.aetherdb.rpc.api.RpcEndpoint;
import io.aetherdb.rpc.api.RpcRetryClass;

import org.junit.jupiter.api.Test;

class RemoteRetryPolicyTest {
    private static final RpcEndpoint CURRENT = RpcEndpoint.of("127.0.0.1", 1001);
    private static final RpcEndpoint LEADER = RpcEndpoint.of("127.0.0.1", 1002);
    private final RemoteRetryPolicy policy = new RemoteRetryPolicy();

    @Test
    void followsLeaderRedirectBeforeAttemptBudgetIsExhausted() {
        RemoteRetryDecision decision =
                policy.decide(
                        ClientStatus.NOT_LEADER,
                        RpcRetryClass.NEVER,
                        CURRENT,
                        LEADER,
                        false,
                        1,
                        3);

        assertThat(decision.action()).isEqualTo(RetryAction.RETRY_PREFERRED_LEADER);
        assertThat(decision.nextEndpoint()).isEqualTo(LEADER);
    }

    @Test
    void uncertainWriteStopsEvenWhenRetryClassWouldOtherwiseAllowRetry() {
        RemoteRetryDecision decision =
                policy.decide(
                        ClientStatus.INDETERMINATE,
                        RpcRetryClass.IDEMPOTENT,
                        CURRENT,
                        LEADER,
                        true,
                        1,
                        3);

        assertThat(decision.action()).isEqualTo(RetryAction.STOP_UNCERTAIN);
    }

    @Test
    void deduplicatedWritesCanRetryTransientStatusesButPlainWritesCannot() {
        RemoteRetryDecision deduplicated =
                policy.decide(
                        ClientStatus.RESOURCE_EXHAUSTED,
                        RpcRetryClass.DEDUP_REQUIRED,
                        CURRENT,
                        null,
                        true,
                        1,
                        3);
        RemoteRetryDecision unsafe =
                policy.decide(
                        ClientStatus.RESOURCE_EXHAUSTED,
                        RpcRetryClass.DEDUP_REQUIRED,
                        CURRENT,
                        null,
                        false,
                        1,
                        3);

        assertThat(deduplicated.action()).isEqualTo(RetryAction.RETRY_SAME_ENDPOINT);
        assertThat(unsafe.action()).isEqualTo(RetryAction.COMPLETE);
    }

    @Test
    void attemptBudgetStopsRetryLoop() {
        RemoteRetryDecision decision =
                policy.decide(
                        ClientStatus.DEADLINE_EXCEEDED,
                        RpcRetryClass.IDEMPOTENT,
                        CURRENT,
                        null,
                        false,
                        3,
                        3);

        assertThat(decision.action()).isEqualTo(RetryAction.COMPLETE);
        assertThat(decision.reason()).contains("attempt budget");
    }
}
