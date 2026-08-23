package io.aetherdb.security.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Parsed Aether node identity from certificate URI SAN. */
public record NodeCertificateIdentity(UUID clusterId, UUID nodeId) {
    private static final String SCHEME = "aether-node";

    public NodeCertificateIdentity {
        UUID zero = new UUID(0, 0);
        if (clusterId == null || nodeId == null || clusterId.equals(zero) || nodeId.equals(zero))
            throw new IllegalArgumentException("invalid node certificate identity");
    }

    public static NodeCertificateIdentity parse(String sanUri) {
        Objects.requireNonNull(sanUri, "sanUri");
        URI uri;
        try {
            uri = new URI(sanUri);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("invalid node identity URI", failure);
        }
        if (!SCHEME.equals(uri.getScheme()))
            throw new IllegalArgumentException("unsupported node identity URI scheme");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("node identity URI must not contain query or fragment");
        String cluster = uri.getHost();
        String path = uri.getPath();
        if (cluster == null || path == null || !path.matches("/[0-9a-fA-F-]{36}"))
            throw new IllegalArgumentException("invalid node identity URI shape");
        try {
            return new NodeCertificateIdentity(
                    UUID.fromString(cluster.toLowerCase(Locale.ROOT)),
                    UUID.fromString(path.substring(1).toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid node identity UUID", failure);
        }
    }

    public String toSanUri() {
        return SCHEME + "://" + clusterId + "/" + nodeId;
    }
}
