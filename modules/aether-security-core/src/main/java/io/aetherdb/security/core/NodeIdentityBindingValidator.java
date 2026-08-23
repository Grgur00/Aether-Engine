package io.aetherdb.security.core;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Validates that a certificate URI SAN binds to expected Aether cluster and node IDs. */
public final class NodeIdentityBindingValidator {
    private static final int URI_SAN = 6;

    private NodeIdentityBindingValidator() {}

    public static NodeCertificateIdentity validate(
            X509Certificate certificate, UUID expectedClusterId, UUID expectedNodeId) {
        Objects.requireNonNull(certificate, "certificate");
        try {
            certificate.checkValidity();
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names == null) throw new NodeIdentityValidationException("certificate has no SANs");
            return validateSanEntries(names, expectedClusterId, expectedNodeId);
        } catch (CertificateParsingException failure) {
            throw new NodeIdentityValidationException("cannot parse certificate SANs", failure);
        } catch (java.security.cert.CertificateException failure) {
            throw new NodeIdentityValidationException("certificate is not currently valid", failure);
        }
    }

    public static NodeCertificateIdentity validateSanUris(
            Collection<String> sanUris, UUID expectedClusterId, UUID expectedNodeId) {
        Objects.requireNonNull(sanUris, "sanUris");
        java.util.ArrayList<List<?>> entries = new java.util.ArrayList<>();
        for (String uri : sanUris) entries.add(List.of(URI_SAN, uri));
        return validateSanEntries(entries, expectedClusterId, expectedNodeId);
    }

    private static NodeCertificateIdentity validateSanEntries(
            Collection<List<?>> names, UUID expectedClusterId, UUID expectedNodeId) {
        Objects.requireNonNull(expectedClusterId, "expectedClusterId");
        Objects.requireNonNull(expectedNodeId, "expectedNodeId");
        NodeIdentityValidationException lastFailure = null;
        for (List<?> entry : names) {
            if (entry.size() < 2 || !(entry.get(0) instanceof Integer type) || type != URI_SAN)
                continue;
            if (!(entry.get(1) instanceof String uri)) continue;
            try {
                NodeCertificateIdentity parsed = NodeCertificateIdentity.parse(uri);
                if (!parsed.clusterId().equals(expectedClusterId))
                    throw new NodeIdentityValidationException("node certificate cluster mismatch");
                if (!parsed.nodeId().equals(expectedNodeId))
                    throw new NodeIdentityValidationException("node certificate node mismatch");
                return parsed;
            } catch (IllegalArgumentException | NodeIdentityValidationException failure) {
                lastFailure =
                        failure instanceof NodeIdentityValidationException typed
                                ? typed
                                : new NodeIdentityValidationException(failure.getMessage(), failure);
            }
        }
        if (lastFailure != null) throw lastFailure;
        throw new NodeIdentityValidationException("certificate has no Aether node URI SAN");
    }
}
