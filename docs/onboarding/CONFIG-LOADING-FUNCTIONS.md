# Configuration Loading and Validation Functions

[Function index](FUNCTION-INDEX.md) | [Operations](OPERATIONS-AND-DEBUGGING.md) | [Persistent internals](PERSISTENT-INTERNALS.md)

This reference covers the explicit declarations in nine configuration files:
the immutable value model, setting metadata, registry, loader, validator, exception,
and three enums. Runtime conversion examples explain consumer boundaries; they are
not a complete function inventory of the engine, Raft, or RPC modules. The
[configuration change reference](CONFIG-CHANGE-FUNCTIONS.md) explains reload and
cluster-change decisions separately from loading a configuration.

## Architectural Boundary

The loader resolves strings; the validator checks registered values and selected
cross-setting rules; each consumer converts the result into its own runtime model.
None of these steps starts a server, resolves an encryption key, changes an existing
database, or makes every registered setting operational.

| Stage | Input and output | Responsibility |
| --- | --- | --- |
| `readProperties` | Optional file to string map | Java properties parsing with a UTF-8 reader |
| `fromEnvironment` | Caller-supplied environment to registered values | Registered-name mapping only |
| `load` | File, environment, overrides to `AetherConfiguration` | Merge precedence, immutable copy, validation |
| `validate` | Configuration and registry | Unknown names, scalar values, hard-coded combinations |
| Consumer `from` methods | Validated strings to runtime fields | Narrowing, domain-specific construction, application |

Precedence is **file < environment < explicit overrides**. Registry defaults are
looked up on demand, not inserted into the returned configuration's `values()` map.
An absent explicit value can therefore have an effective default while `get` is empty.

## Value and Setting Models

### AetherConfiguration

Source: [AetherConfiguration.java](../../modules/aether-config/src/main/java/io/aetherdb/config/AetherConfiguration.java).

| Function | Behavior and boundary |
| --- | --- |
| `AetherConfiguration(values)` | Requires the map and takes `Map.copyOf`; rejects null keys/values. It does not validate setting names or semantics. |
| `get(name)` | Returns the explicitly stored value as an `Optional`; does not consult defaults. |
| `getOrDefault(setting)` | Looks up `setting.name()` or returns `setting.defaultValue()`; does not parse, validate, or store the default. |
| Implicit `values()` accessor | Returns the immutable copied map. Its iteration order is not promised. |

### ConfigSetting

Source: [ConfigSetting.java](../../modules/aether-config/src/main/java/io/aetherdb/config/ConfigSetting.java).

`ConfigSetting(...)` validates the name against `aether\.[a-z0-9_.]+`, requires type,
default and scope, copies the non-null `unsafeCombinations` list, requires a nonblank
validation code, requires compatibility epoch >= 1, and checks minimum <= maximum
when both bounds exist. Null bounds mean no bound in that direction.

The implicit record accessors expose name, type, default, bounds, scope,
`hotReloadable`, `restartRequired`, `securitySensitive`, `compatibilityEpoch`,
`unsafeCombinations`, and `validationErrorCode`. These are metadata, not callbacks.
Construction does not parse the default, validate its range, or prohibit contradictory
reload/restart flags. The validator does not interpret the unsafe-combination strings.

### Enums and Validation Exception

| Type | Values or function | Meaning here |
| --- | --- | --- |
| [ConfigType](../../modules/aether-config/src/main/java/io/aetherdb/config/ConfigType.java) | `STRING`, `BOOLEAN`, `INTEGER`, `LONG` | Selects scalar validation; both numeric types are initially parsed as `long`. |
| [ConfigScope](../../modules/aether-config/src/main/java/io/aetherdb/config/ConfigScope.java) | `NODE_LOCAL`, `CLUSTER_WIDE`, `EMBEDDED_ONLY` | Descriptive scope; scalar validation does not establish cluster agreement. |
| [ConfigSource](../../modules/aether-config/src/main/java/io/aetherdb/config/ConfigSource.java) | `FILE`, `ENVIRONMENT`, `OVERRIDE` | Source labels; `AetherConfiguration` does not retain per-value provenance. |
| [ConfigValidationException](../../modules/aether-config/src/main/java/io/aetherdb/config/ConfigValidationException.java) | `ConfigValidationException(message)` | Runtime exception passing the message to its superclass; no structured setting-code field. |

## Registry Functions

Source: [AetherConfigRegistry.java](../../modules/aether-config/src/main/java/io/aetherdb/config/AetherConfigRegistry.java).

| Function | Behavior |
| --- | --- |
| Private `AetherConfigRegistry(settings)` | Copies the supplied settings map with `Map.copyOf`. |
| `defaults()` | Constructs a new registry containing 40 declared settings; not a shared singleton and not a validated configuration. |
| `settings()` | Exposes the immutable map; construction through a linked map does not guarantee the returned map's iteration order. |
| `require(name)` | Returns a registered definition or throws `IllegalArgumentException` for an unknown name. |
| Private `add(...)` | Builds a `ConfigSetting` with epoch 1, empty unsafe-combination list, and generated error code, then calls `put`. It has no duplicate-name rejection. |
| Private `validationErrorCode(name)` | Removes `aether.`, uppercases with `Locale.ROOT`, replaces dots with underscores, and prefixes `CFG_`. Existing underscores remain. |

All registered definitions have epoch 1. Only the KEK provider is marked sensitive.
No default uses `EMBEDDED_ONLY`. The generated codes are available metadata, but
the validator's exceptions currently contain human-readable messages instead.

### Declared Default Groups

Names below omit the common `aether.` prefix. MiB/GiB are binary units; configuration
strings contain decimal byte counts, not unit suffixes.

| Group | Defaults | Selected bounds |
| --- | --- | --- |
| Storage | `storage.path` empty; lock timeout 30 s; disk pressure enabled | Lock timeout 1..3600 s |
| WAL | `GROUP_SYNC`; segment 64 MiB; force timeout 5 s | Segment 1..64 MiB; force timeout 1..300 s |
| Mutable and immutable storage | Native memtable 64 MiB; immutable limit 4; data block 4096 bytes; cache 128 MiB | Native 1..512 MiB; limit 1..64; block 1024..65536; cache 0..8 GiB |
| Compaction and snapshots | Enabled; 2 jobs; bandwidth 128 MiB/s; 64 open snapshots | Jobs 1..64; bandwidth 0..16 GiB/s; snapshots 1..4096 |
| RPC | Host `0.0.0.0`; port 9483; frame 1 MiB; message 8 MiB; 256 streams; inbound/outbound 64 MiB; timeout 30 s | Port/streams up to 65535; frame up to 1 MiB; message up to 64 MiB; buffers 1 MiB..8 GiB |
| Raft | Election 300..600 ms; heartbeat 100 ms; snapshot threshold 100000 entries; uncommitted limit 256 MiB | Election 50..600000 ms; heartbeat 10..60000 ms; threshold positive; bytes 1 MiB..8 GiB |
| Backup | Path empty; bandwidth 128 MiB/s | Bandwidth 0..16 GiB/s |
| Security | `production`; TLS, mTLS, authorization and at-rest encryption enabled; audit fail-open disabled; KEK provider empty | Cross-setting policy below |
| Observability and budget | Metrics enabled; tracing disabled; log level `INFO`; memory budget 512 MiB | Budget 32 MiB..64 GiB |

An empty configuration **fails default validation**: production encryption requires
a nonblank KEK provider, and its registered default is empty. A nonblank provider
name satisfies this particular check; it does not prove a key provider exists.
Development profile bypasses the production-only security combinations, not all
other scalar, timing, memory, or enum validation.

## Loader Functions

Source: [AetherConfigLoader.java](../../modules/aether-config/src/main/java/io/aetherdb/config/AetherConfigLoader.java).

### Construction and load

`AetherConfigLoader(registry)` requires a registry and constructs a validator using
that same registry. `defaults()` constructs a loader with a newly built default registry.

`load(propertiesPath, environment, overrides)` optionally reads a file, merges mapped
environment values, then explicit overrides. It requires environment and overrides,
constructs the immutable value model, validates it, and returns only on success.
I/O errors propagate as `IOException`; validation failures are runtime exceptions.
A null path means no file, not a default filename. The method does not call
`System.getenv()` itself: the caller chooses which environment map to pass.

### readProperties and fromEnvironment

Private `readProperties(path)` opens `Files.newBufferedReader`, uses `Properties.load(Reader)`,
closes the reader, and copies string property names/values. The reader defaults to
UTF-8, rather than the byte-stream overload's properties encoding. Escapes and
duplicate-key handling come from `Properties`; there is no custom file format.

Private `fromEnvironment(environment)` iterates registered setting names, uppercases
with `Locale.ROOT`, replaces each dot with an underscore, and includes non-null matches.
For example, `aether.rpc.max_frame_bytes` maps to `AETHER_RPC_MAX_FRAME_BYTES`.
Unrelated environment variables are ignored. Unknown file or override names, in
contrast, survive merging and are rejected by validation.

### redactedDiagnosticView

`redactedDiagnosticView(configuration)` returns an immutable map of every registered
setting's effective value. A nonblank sensitive value becomes `REDACTED`; blank
sensitive values remain blank. Explicit unknown keys become `UNKNOWN`, not their raw
values. This method does not validate the input, and the configuration's own `values()`
still contains original values. Only definitions marked sensitive receive redaction.

## Validator Functions

Source: [AetherConfigValidator.java](../../modules/aether-config/src/main/java/io/aetherdb/config/AetherConfigValidator.java).

### Entry Points and Scalar Checks

| Function | Behavior |
| --- | --- |
| `AetherConfigValidator(registry)` | Requires and retains the registry. |
| `validate(configuration)` | Requires input; rejects explicit unknown keys; checks every registered effective value including defaults; then checks combinations. Throws on the first encountered failure, not an aggregate report. |
| `defaults()` | Constructs a validator using a new default registry. |
| Static `validate(values)` | Copies the map into `AetherConfiguration`, then runs default validation. |
| Private `validateValue(setting, raw)` | Boolean must be exactly true/false ignoring case; numeric values use `Long.parseLong` and inclusive declared bounds; strings receive no generic semantic check. |

Values are not trimmed. `INTEGER` does not itself narrow to Java `int`; consumers
can enforce narrower limits later. Invalid numeric syntax is converted to a
`ConfigValidationException` message without preserving the parsing cause.

### Combination Checks

Private `validateUnsafeCombinations(configuration)` implements these rules directly:

- Profile is `production` or `development`, ignoring case.
- Production requires TLS, mTLS and authorization, rejects audit fail-open, and
  requires a nonblank KEK provider when at-rest encryption is enabled.
- WAL durability is `SYNC` or `GROUP_SYNC`; log level is `TRACE`, `DEBUG`, `INFO`,
  `WARN`, or `ERROR`, ignoring case.
- RPC message limit must be at least its frame limit.
- Minimum election timeout must be strictly greater than twice the heartbeat;
  maximum election timeout must be at least the minimum.
- Cache bytes + native memtable bytes + background jobs * target data-block bytes
  must not exceed the declared memory budget.

The last rule is a limited accounting formula, not actual memory reservation or
an estimate of all JVM/native/transport allocations. Arithmetic is ordinary `long`
addition/multiplication; current registry bounds keep these terms within range.

### Private Lookup and Predicate Helpers

| Function | Behavior |
| --- | --- |
| `validateEnum(configuration, name, allowed)` | Uppercases the effective value and rejects values outside the supplied set. |
| `requireTrue(configuration, name)` | Throws a production-requires message when `isTrue` is false. |
| `requireFalse(configuration, name)` | Throws a production-rejects message when `isTrue` is true. |
| `isTrue(configuration, name)` | Uses `Boolean.parseBoolean` on `value`; strict scalar checking precedes its use in normal validation. |
| `longValue(configuration, name)` | Parses the effective value as a long; assumes scalar validation already succeeded. |
| `value(configuration, name)` | Requires the named registry definition, then uses `getOrDefault`. |

## Runtime Consumption Is a Separate Step

| Consumer | Source-backed conversion | Boundary |
| --- | --- | --- |
| [PersistentAetherDatabase.RuntimeConfiguration](../../modules/aether-engine/src/main/java/io/aetherdb/engine/PersistentAetherDatabase.java) | Native bytes, WAL segment bytes, snapshot maximum, compaction/disk-pressure switches, immutable limit and durability mode | Uses default compaction configuration and default write admission timeout; this conversion is not an application of every registry setting. |
| [RaftRuntimeConfiguration](../../modules/aether-raft-core/src/main/java/io/aetherdb/raft/core/RaftRuntimeConfiguration.java) | Five Raft values become durations and long thresholds after global validation | Configuration construction does not start elections, install snapshots, or reserve log bytes. |
| [RpcTransportConfiguration](../../modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport/RpcTransportConfiguration.java) | Frame/message/stream/buffer limits; outbound bytes become 1024-byte permit units | Its `intValue` uses `Math.toIntExact`; registry-valid buffers above `Integer.MAX_VALUE` cannot be represented here. |

RPC conversion rounds outbound bytes down to whole permits, with a minimum of one.
Do not infer that a valid profile creates TLS credentials, that `hotReloadable` changes
an already-open component, or that `CLUSTER_WIDE` alone distributes a setting.

## Tests and Coverage Limits

[AetherConfigLoaderTest](../../modules/aether-config/src/test/java/io/aetherdb/config/AetherConfigLoaderTest.java)
checks precedence and sensitive redaction.
[AetherConfigValidatorTest](../../modules/aether-config/src/test/java/io/aetherdb/config/AetherConfigValidatorTest.java)
checks production policy, development allowance, timing, durability, RPC relation,
memory budget, registry metadata, and sensitive classification.
These tests do not establish that every setting is wired into a runtime subsystem.

The documentation inventory checks explicit declaration names in the nine files
listed above. It does not certify overload behavior, implicit record members,
reload transactions, cluster compatibility, or live security enforcement.
