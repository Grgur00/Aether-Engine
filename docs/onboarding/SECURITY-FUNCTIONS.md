# Authorization, Audit, and Node Identity Functions

[Function index](FUNCTION-INDEX.md) | [Crypto](CRYPTO-FUNCTIONS.md) | [Operations](OPERATIONS-AND-DEBUGGING.md)

Sources: [security API](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api)
and [security core](../../modules/aether-security-core/src/main/java/io/aetherdb/security/core).
This guide covers **49 explicit declarations across 22 implementation files**:
24 API declarations in 14 files and 25 core declarations in eight files. Enum
helpers, generated record methods and implicit constructors are outside the count.

## Enforcement Architecture

```text
transport/provider validation -> SecurityPrincipal (caller-supplied identity)
  -> permission + resource -> RbacAuthorizer -> allow/deny decision
caller enforcement -> protected operation + AuditEvent -> chosen AuditSink
certificate SAN -> identity parser -> expected cluster/node comparison
```

The arrows are integration responsibilities, not an automatically enforced
pipeline. These modules do not intercept every database/RPC call, authenticate
a principal record, implement TLS, choose encryption keys or provide a concrete
key-provider service. Configuration naming a provider is not its implementation.
Authorization decisions must be acted on by the caller; mandatory-audit failure
must also be handled at the appropriate operation boundary by that integration.

The API contains a concrete immutable RBAC policy/authorizer as well as contracts.
Core supplies audit sinks, formatting/redaction and certificate identity helpers.
Neither authorizing nor recording an event automatically invokes the other.

## Principal And Permission Functions

Sources: [SecurityPrincipal.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/SecurityPrincipal.java),
[AetherPermission.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/AetherPermission.java),
[AuthorizationDecision.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/AuthorizationDecision.java),
[AetherAuthorizer.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/AetherAuthorizer.java).

| Function | Behavior and boundaries |
| --- | --- |
| `SecurityPrincipal.SecurityPrincipal(kind, id, attributes)` | Requires nonnull kind/map and nonnull/nonblank ID. Copies attributes with Map.copyOf, rejecting null keys/values. Does not trim ID, authenticate it or restrict attribute names/count/length. Invalid ID raises IllegalArgumentException; null kind/map/elements raise NullPointerException. |
| `SecurityPrincipal.embeddedOwner()` | Creates EMBEDDED_PROCESS principal with ID embedded:owner and empty attributes. It does not confer a role or bypass RBAC. |
| `AetherPermission.AetherPermission(name)` | Requires exact lowercase domain.action matching [a-z][a-z0-9_]* in each component. Null, uppercase, wildcard, extra dots or whitespace raise IllegalArgumentException. Applies Locale.ROOT lowercasing after the already-lowercase validation; uppercase input is not normalized into acceptance. |
| `AetherPermission.of(name)` | Constructs the same validated record; no permission registry lookup. |
| `AuthorizationDecision.AuthorizationDecision(allowed, reasonCode)` | Requires nonnull/nonblank reason; invalid reason raises IllegalArgumentException. Any other String spelling is retained; no stable-code registry, length bound or validation against allowed. |
| `AuthorizationDecision.allow(reasonCode)` | Requires nonnull reason through Objects.requireNonNull, then creates allowed=true. Null therefore raises NullPointerException rather than the canonical constructor's IllegalArgumentException. |
| `AuthorizationDecision.deny(reasonCode)` | Same factory validation with allowed=false. |
| `AetherAuthorizer.authorize(principal, permission, resource)` | Functional-interface contract returning a decision. No default implementation or interface-level null handling/enforcement; see RbacAuthorizer for concrete behavior. |

[PrincipalKind.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/PrincipalKind.java)
defines NODE, CLIENT, SERVICE and EMBEDDED_PROCESS. These labels are metadata,
not proof of origin. Generated attributes() exposes an immutable map. A caller
can construct a principal without passing through any authentication provider.

## RBAC Functions

Sources: [ResourceId.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/ResourceId.java),
[RoleBinding.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/RoleBinding.java),
[RoleDefinition.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/RoleDefinition.java),
[RoleGrant.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/RoleGrant.java),
[RbacPolicy.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/RbacPolicy.java),
[RbacAuthorizer.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/RbacAuthorizer.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ResourceId.ResourceId(kind, id)` | Rejects null/blank kind or ID with IllegalArgumentException. Keeps spelling unchanged, with no syntax or length bound. CLUSTER is cluster/*; ANY is */*. |
| `ResourceId.covers(requested)` | Requires nonnull requested resource. Each scope field matches either the whole-field literal * or exact requested text. No prefix glob, path inheritance, case folding or embedded wildcard interpretation. |
| `RoleBinding.RoleBinding(principalId, roleName)` | Rejects null/blank values with IllegalArgumentException. No lookup, kind binding or ID normalization. |
| `RoleDefinition.RoleDefinition(name, grants)` | Validates nonblank name, then freezes grants through Set.copyOf. Null set/elements raise NullPointerException; duplicate grants collapse. Empty roles are allowed. |
| `RoleGrant.RoleGrant(permission, resourceScope)` | Requires nonnull permission and scope; both failures raise NullPointerException. |
| `RoleGrant.permits(requestedPermission, requestedResource)` | Package-private: exact permission-record equality AND scope.covers. A different/null permission short-circuits to false; matching permission with null resource reaches covers and throws. No deny grant or permission implication. |
| `RbacPolicy.RbacPolicy(roles, bindings)` | Freezes map/list and rejects every binding whose roleName is absent as a map key. Null containers/elements raise NullPointerException. Does not require each map key to equal its RoleDefinition.name or forbid duplicate bindings. |
| `RbacPolicy.empty()` | Returns empty immutable roles/bindings. RbacAuthorizer with this policy denies every valid request. |
| `RbacPolicy.rolesFor(principal)` | Package-private: scans bindings whose principalId exactly equals principal.id, maps role names through policy map and collects an unmodifiable set. Does not consult principal kind or attributes. Multiple bindings to the same role collapse; no sorted role order is promised. |
| `RbacAuthorizer.RbacAuthorizer(policy)` | Requires nonnull immutable policy and retains it. No reload/setter or external policy store. |
| `RbacAuthorizer.authorize(principal, permission, resource)` | Requires all three arguments nonnull. Loops matching roles/grants; first permitting grant returns allow with RBAC_ROLE_GRANT. If none matches, returns deny with RBAC_DENY_DEFAULT. No I/O, audit write or protected operation occurs. |

Two principals with the same ID but different kinds/attributes receive the same
bindings from this implementation. Policy identity is therefore an integration
design decision, not automatically kind-scoped. Permission equality is exact;
resource wildcards do not create wildcard permissions. Roles are additive allow
grants with a default denial; there is no deny precedence, hierarchy, expiration
or conditional attribute rule. Map keys, not role display names, resolve bindings.

## Audit Contract Functions

Sources: [AuditEvent.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/AuditEvent.java),
[AuditSink.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/AuditSink.java),
[AuditUnavailableException.java](../../modules/aether-security-api/src/main/java/io/aetherdb/security/api/AuditUnavailableException.java).

| Function | Behavior and boundaries |
| --- | --- |
| `AuditEvent.AuditEvent(eventTime, eventId, clusterId, nodeId, principalId, principalKind, operation, resourceKind, resourceId, decision, reasonCode, requestId, traceId)` | Requires nonnull time/UUID/kind, and nonblank operation/resourceKind/resourceId/decision/reasonCode. Identity strings, requestId and traceId may be null. Does not check UUID nonzero, permission syntax, decision vocabulary, event ordering, lengths or absence of secrets. |
| `AuditEvent.isBlank(value)` | Private null-or-String.isBlank predicate for mandatory text fields. |
| `AuditSink.record(event)` | Functional-interface contract that can throw checked AuditUnavailableException. No interface-level durability guarantee or automatic fail-closed operation enforcement. Concrete sinks differ. |
| `AuditUnavailableException.AuditUnavailableException(message, cause)` | Stores supplied message/cause through Exception; null cause is allowed. |
| `AuditUnavailableException.AuditUnavailableException(message)` | Message-only checked exception constructor. |

An audit record is immutable metadata but not a signed statement or evidence that
the decision was enforced. Event IDs are supplied by callers; nothing here
deduplicates events or ties their commit to a database transaction.

## Audit Storage Functions

Sources: [CompositeAuditSink.java](../../modules/aether-security-core/src/main/java/io/aetherdb/security/core/CompositeAuditSink.java),
[FileAuditSink.java](../../modules/aether-security-core/src/main/java/io/aetherdb/security/core/FileAuditSink.java),
[InMemoryAuditSink.java](../../modules/aether-security-core/src/main/java/io/aetherdb/security/core/InMemoryAuditSink.java).

| Function | Behavior and boundaries |
| --- | --- |
| `CompositeAuditSink.CompositeAuditSink(delegates)` | Requires nonnull list, copies it with List.copyOf, rejects null elements and rejects empty list with IllegalArgumentException. Retains delegate objects; does not own/close them. |
| `CompositeAuditSink.record(event)` | Invokes delegates in list order. Collects checked AuditUnavailableException failures as suppressed exceptions on a new aggregate error, continues calling remaining delegates, then throws if any failed. Unchecked failures propagate immediately and stop fan-out. Does not reject null itself, roll back successful writes or synchronize concurrent calls. |
| `FileAuditSink.FileAuditSink(path, forceOnRecord)` | Requires nonnull path. Creates parent directories based on absolute normalized path, then opens the supplied path with CREATE/WRITE/APPEND. IOException becomes checked AuditUnavailableException. No path sandbox, symlink rejection, rotation, exclusive lock, permissions policy or parent-directory force. |
| `FileAuditSink.record(event)` | Synchronized per sink instance. Formats event plus System.lineSeparator as UTF-8 before the try block, writes until ByteBuffer is exhausted and optionally calls channel.force(false). IOException becomes AuditUnavailableException. Formatting/null errors are unchecked; partial bytes may already be appended before an I/O error. |
| `FileAuditSink.close()` | Synchronized channel.close; IOException becomes AuditUnavailableException. Does not explicitly force before closing or close other sinks. Record after close reaches a checked append failure. |
| `InMemoryAuditSink.record(event)` | Synchronized append to an unbounded ArrayList. Stores the original immutable event reference, accepts null and provides no persistence. Implicit constructor simply starts empty. |
| `InMemoryAuditSink.events()` | Synchronized List.copyOf snapshot, immutable and detached from subsequent additions. A previously recorded null causes NullPointerException here. No clearing, eviction or bounded capacity. |

Composite failure is not an atomic audit transaction: some sinks may already
contain the event, and retrying can duplicate it. File synchronization protects
only a single instance, not independent writers to the same file. forceOnRecord
controls a local force(false) call, not remote replication or a transaction with
the protected operation. Runtime errors are not uniformly converted to the
checked audit-unavailable contract.

## Audit Formatting And Redaction Functions

Sources: [AuditJsonFormatter.java](../../modules/aether-security-core/src/main/java/io/aetherdb/security/core/AuditJsonFormatter.java),
[SecretRedactor.java](../../modules/aether-security-core/src/main/java/io/aetherdb/security/core/SecretRedactor.java).

| Function | Behavior and boundaries |
| --- | --- |
| `AuditJsonFormatter.AuditJsonFormatter()` | Private empty constructor for static formatting. |
| `AuditJsonFormatter.format(event)` | Builds one JSON object in fixed field order, all values quoted strings. Hash-redacts cluster/node/principal/resource identifiers. Emits time, event UUID, kind, operation, resource kind, decision, reason, request ID and trace ID without hash redaction. Null request/trace become empty strings. Adds no line separator; null event fails unchecked. |
| `AuditJsonFormatter.redactNullable(kind, value)` | Returns empty string for null/blank text, otherwise delegates to SecretRedactor.redact. Blank identity fields are not represented by the redactor's empty marker. |
| `AuditJsonFormatter.append(json, name, value)` | Appends a quoted field name, colon and escaped quoted value, returning the same builder. Names are internal constants and not escaped by this helper. |
| `AuditJsonFormatter.escape(value)` | Escapes quote, backslash, backspace, form feed, newline, carriage return and tab; other characters below U+0020 become four-digit backslash-u sequences. Other Java characters, including surrogate code units, are appended unchanged. No strict Unicode normalization or length cap. |
| `SecretRedactor.SecretRedactor()` | Private empty constructor. |
| `SecretRedactor.redact(kind, secret)` | Rejects null/blank kind. Null/empty secret yields REDACTED:kind:empty. Otherwise hashes UTF-8 secret and returns REDACTED:kind: plus lowercase hex of the first eight digest bytes (16 hex characters). Whitespace-only secrets are hashed. Kind is retained literally and excluded from digest input. |
| `SecretRedactor.redactBytes(kind, secret)` | Same label/empty policy, hashing the supplied bytes directly. Does not clone or erase them. |
| `SecretRedactor.sha256(input)` | Creates SHA-256 digest per call; unavailable algorithm becomes IllegalStateException with cause. No salt, secret key or domain separation is added. |

Hash labels provide deterministic correlation, not anonymization or encryption.
The same secret produces the same digest suffix across kinds, and only 64 digest
bits are retained. Formatting does not scrub free-form operation/reason/request/
trace text. Callers must not treat the word redacted as a guarantee that every
field is secret-free. File output newline handling is platform-specific.

## Node Identity Functions

Sources: [NodeCertificateIdentity.java](../../modules/aether-security-core/src/main/java/io/aetherdb/security/core/NodeCertificateIdentity.java),
[NodeIdentityBindingValidator.java](../../modules/aether-security-core/src/main/java/io/aetherdb/security/core/NodeIdentityBindingValidator.java),
[NodeIdentityValidationException.java](../../modules/aether-security-core/src/main/java/io/aetherdb/security/core/NodeIdentityValidationException.java).

| Function | Behavior and boundaries |
| --- | --- |
| `NodeCertificateIdentity.NodeCertificateIdentity(clusterId, nodeId)` | Rejects null or zero UUID in either position with IllegalArgumentException. No membership lookup or certificate possession proof. |
| `NodeCertificateIdentity.parse(sanUri)` | Requires nonnull String and parses java.net.URI. Requires exact lowercase aether-node scheme, no raw query/fragment, nonnull host, and decoded path matching slash + 36 hex/hyphen characters. Lowercases UUID text before UUID.fromString; constructs nonzero identity. URI syntax, shape and UUID errors have separate IllegalArgumentException messages/causes. Does not explicitly reject URI port/user-info or require canonical raw URI spelling. |
| `NodeCertificateIdentity.toSanUri()` | Returns canonical aether-node://clusterUuid/nodeUuid text using UUID.toString. Does not create/sign a certificate. |
| `NodeIdentityBindingValidator.NodeIdentityBindingValidator()` | Private empty constructor. URI SAN type is integer 6. |
| `NodeIdentityBindingValidator.validate(certificate, expectedClusterId, expectedNodeId)` | Requires certificate, calls checkValidity using current time, obtains subject alternative names, rejects absent SANs and delegates matching. CertificateParsingException becomes cannot-parse identity exception; other CertificateException becomes not-currently-valid exception. Does not validate issuer chain, signature trust, revocation, TLS handshake or key usage. |
| `NodeIdentityBindingValidator.validateSanUris(sanUris, expectedClusterId, expectedNodeId)` | Requires collection, converts each String to synthetic [6, URI] SAN entry and delegates. Null element fails through List.of. Does not inspect a real certificate or check dates. |
| `NodeIdentityBindingValidator.validateSanEntries(names, expectedClusterId, expectedNodeId)` | Requires expected IDs nonnull. Skips entries with fewer than two fields, noninteger/non-6 type or nonstring URI. Parses candidates and requires exact cluster/node equality. Returns the first match even after earlier invalid/mismatched candidates. If none matches, throws the last candidate failure or no-Aether-URI error. Null entry is not guarded. |
| `NodeIdentityValidationException.NodeIdentityValidationException(message)` | Message-only RuntimeException constructor. |
| `NodeIdentityValidationException.NodeIdentityValidationException(message, cause)` | Preserves message/cause via RuntimeException. |

An identity match is not trust-chain validation. Multiple SANs are accepted if any
one matches; the helper does not reject duplicates or insist on exactly one Aether
identity. Zero expected IDs are not explicitly rejected at entry, but cannot
match a valid parsed identity. Decoded URI path shape is checked, not raw encoded
spelling. The parser's acceptance rules should not be inferred from its canonical
toSanUri output alone.

## Verification And Integration Boundaries

Compiler-tree coverage inventories every explicit declaration in both modules.
Current tests cover RBAC default denial, exact grants and unknown-role rejection;
audit redaction, memory retention, composite checked failure, file append/closed
failure; node URI parsing and expected-node matching; and stable redaction.
They do not exhaustively cover kind/ID collisions, wildcard combinations, null
events, concurrent file writers, partial-write recovery, URI user-info/port forms,
multiple SAN candidates or real certificate trust chains. No concrete key-provider
service is supplied by these two modules; [crypto](CRYPTO-FUNCTIONS.md) documents
the local wrapping helper separately. This reference changes no runtime behavior
and does not certify end-to-end security enforcement or a production deployment.
