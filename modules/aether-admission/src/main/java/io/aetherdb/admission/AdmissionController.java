package io.aetherdb.admission;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Pure reusable Chapter 31 admission evaluator. */
public final class AdmissionController {
    public AdmissionDecision evaluate(
            AdmissionPolicy policy, AdmissionSnapshot snapshot, AdmissionRequest request) {
        if (request.draining()) {
            return new AdmissionDecision(
                    AdmissionOutcome.DRAINING_REJECTED,
                    List.of("node is draining"),
                    Duration.ZERO);
        }
        List<String> emergency = new ArrayList<>();
        List<String> exhausted = new ArrayList<>();
        List<String> slowed = new ArrayList<>();
        double worstSoftRatio = 0;
        for (ResourceLimit limit : policy.orderedLimits()) {
            long current = snapshot.value(limit.resource());
            long charge = request.charges().getOrDefault(limit.resource(), 0L);
            long projected = addSaturated(current, charge);
            if (projected >= limit.emergencyLimit()) {
                emergency.add("emergency limit reached: " + limit.resource());
            } else if (projected >= limit.hardLimit()) {
                exhausted.add("hard limit reached: " + limit.resource());
            } else if (projected >= limit.softLimit()) {
                slowed.add("soft limit reached: " + limit.resource());
                worstSoftRatio = Math.max(
                        worstSoftRatio,
                        ratio(projected - limit.softLimit(), limit.hardLimit() - limit.softLimit()));
            }
        }
        if (!emergency.isEmpty()) {
            return new AdmissionDecision(
                    AdmissionOutcome.RESOURCE_EXHAUSTED, emergency, Duration.ZERO);
        }
        if (!exhausted.isEmpty()) {
            return new AdmissionDecision(
                    AdmissionOutcome.RESOURCE_EXHAUSTED, exhausted, Duration.ZERO);
        }
        if (!slowed.isEmpty()) {
            return new AdmissionDecision(
                    AdmissionOutcome.REJECTED_BEFORE_ACK,
                    slowed,
                    slowdown(policy.maximumSlowdownDelay(), worstSoftRatio));
        }
        return new AdmissionDecision(AdmissionOutcome.ACCEPTED, List.of(), Duration.ZERO);
    }

    private static long addSaturated(long left, long right) {
        long result = left + right;
        return result < 0 ? Long.MAX_VALUE : result;
    }

    private static double ratio(long value, long range) {
        if (range <= 0) return 1;
        return Math.max(0, Math.min(1, (double) value / range));
    }

    private static Duration slowdown(Duration maximum, double ratio) {
        if (maximum.isZero()) return Duration.ZERO;
        long nanos = Math.max(1, Math.round(maximum.toNanos() * Math.max(0.01, ratio)));
        return Duration.ofNanos(Math.min(maximum.toNanos(), nanos));
    }
}
