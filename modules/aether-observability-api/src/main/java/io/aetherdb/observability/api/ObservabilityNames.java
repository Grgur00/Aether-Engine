package io.aetherdb.observability.api;

import java.util.Set;
import java.util.regex.Pattern;

/** Validators for Aether metric names and bounded labels. */
public final class ObservabilityNames {
    private static final Pattern METRIC =
            Pattern.compile("aether_[a-z][a-z0-9]*(?:_[a-z0-9]+)*(?:_(?:total|seconds|bytes|ratio))?");
    private static final Pattern LABEL = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Set<String> FORBIDDEN_LABELS =
            Set.of(
                    "key",
                    "value",
                    "collection_name",
                    "principal_id",
                    "token",
                    "certificate_subject",
                    "ip_address",
                    "request_id",
                    "trace_id",
                    "schema_fingerprint",
                    "exception_message",
                    "file_path");

    private ObservabilityNames() {}

    public static String requireValidMetricName(String name) {
        if (name == null || !METRIC.matcher(name).matches())
            throw new IllegalArgumentException("invalid Aether metric name");
        return name;
    }

    public static String requireValidLabelName(String name) {
        if (name == null || !LABEL.matcher(name).matches())
            throw new IllegalArgumentException("invalid metric label name");
        if (FORBIDDEN_LABELS.contains(name))
            throw new IllegalArgumentException("forbidden high-cardinality or sensitive label");
        return name;
    }
}
