package io.aetherdb.admission;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable admission decision.
 *
 * @param outcome stable Chapter 31 outcome
 * @param reasons stable operator-facing reasons
 * @param suggestedDelay optional bounded slowdown delay
 */
public record AdmissionDecision(
        AdmissionOutcome outcome, List<String> reasons, Duration suggestedDelay) {
    public AdmissionDecision {
        Objects.requireNonNull(outcome, "outcome");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        suggestedDelay = Objects.requireNonNull(suggestedDelay, "suggestedDelay");
        if (suggestedDelay.isNegative()) throw new IllegalArgumentException("delay must be non-negative");
        if (outcome == AdmissionOutcome.ACCEPTED && !reasons.isEmpty())
            throw new IllegalArgumentException("accepted decision cannot have rejection reasons");
    }

    public boolean accepted() {
        return outcome == AdmissionOutcome.ACCEPTED;
    }
}
