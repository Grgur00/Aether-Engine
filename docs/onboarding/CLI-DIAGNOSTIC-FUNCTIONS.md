# CLI Diagnostics and Configuration Functions

[Function index](FUNCTION-INDEX.md) | [CLI inspection](CLI-INSPECTION-FUNCTIONS.md) | [Configuration loading](CONFIG-LOADING-FUNCTIONS.md) | [Release certification](RELEASE-CERTIFICATION-FUNCTIONS.md)

Source: [AetherCli.java](../../modules/aether-tools/src/main/java/io/aetherdb/tools/AetherCli.java).
This page covers **20 explicit declarations** for diagnostics bundles, config
validation, static command schemas, release evaluation, shared argument/JSON
helpers and the two report/schema records. It complements the inspection page;
checkpoint, backup and repair/salvage function contracts remain separate work.

## Command Ownership

| Command | Inputs and outputs | What it does not do |
| --- | --- | --- |
| diagnostics | New ZIP path, optional --config properties file; current process environment and Java/OS version; text or JSON receipt. | Does not inspect a database, collect runtime stacks/logs, lock a store or certify absence of secrets. |
| config-validate | Properties file, current environment and --set name=value overrides; resolved redacted settings or validation error. | Does not open/reconfigure a database, verify external services or prove deployment readiness. |
| command-schema | No argument returns nine static command schemas; one exact command name returns one schema. | Does not derive schemas from execution, list every dispatcher branch or enforce CLI parsing. |
| release-certify | Supplied release manifest decoded and evaluated; human output or evaluator JSON; readiness determines status. | Does not execute missing tests or independently collect release evidence. |

All four route through the same run dispatch/error mapping documented in the
inspection reference. JSON flags are recognized by membership for diagnostics,
config validation and release certification; command-schema is always JSON and
rejects more than one argument. Some commands ignore extra/unknown flags while
configOverrides rejects unrecognized config options; parsing is not uniform.

## Command and Export Functions

| Declaration | Behavior and failure boundary |
| --- | --- |
| `AetherCli.releaseCertify(arguments)` | Requires manifest path, normalizes it, reads/decode/evaluates release manifest, then chooses evaluator JSON or text via --json membership. Returns 0 if productionReady, otherwise 2. I/O/decode errors propagate to dispatcher. No test execution or evidence download; extra arguments are not exhaustively rejected. |
| `AetherCli.diagnostics(arguments)` | Requires bundle path, normalizes it and rejects an existing destination before export. First --config uses requiredValue; passes System.getenv to writer. Prints JSON mode/bundle/entries or text receipt, returns 0. Path existence check is supplemented by CREATE_NEW in writer. Does not create parent directories or clean a partially written archive. |
| `AetherCli.configValidate(arguments)` | Requires file, parses overrides, uses AetherConfigLoader.defaults to load file/environment/overrides and obtains loader's redacted diagnostic view. Success prints settings and returns 0. Catches ConfigValidationException for valid:false/error JSON or stderr and returns 2. Override parsing occurs outside this local catch; other argument/I/O failures use dispatch status 3/4. |
| `AetherCli.commandSchema(arguments)` | Rejects more than two tokens including command itself. With one requested command selects exact name or throws unknown-schema IllegalArgumentException. Otherwise serializes all static schemas inside mode/commands object. Prints JSON and returns 0; no store/config inspection. |
| `AetherCli.cliCommandSchemas()` | Returns immutable list of nine newly constructed schemas: inspect, verify, backup-create, backup-restore-preflight, backup-restore, backup-restore-drill, release-certify, diagnostics and config-validate. Most list statuses 0,2,3,4; diagnostics lists 0,3,4. Lists selected JSON fields, not every field or a typed JSON Schema. Missing help/version/checkpoint/restore-verify/repair/rebuild/salvage/command-schema entries are not evidence those commands do not exist. |
| `AetherCli.writeDiagnosticsBundle(bundle, config, environment)` | Requires existing normalized destination parent. Opens original bundle path with CREATE_NEW and ZIP stream; writes manifest.json and environment.properties, plus config.properties when supplied. Close finalizes archive; returns normalized absolute path and entry count 2 or 3. No temp-rename, fsync or failure deletion, so exceptions can leave a partial ZIP. Does not validate supplied map/config before opening output. |
| `AetherCli.diagnosticsManifestJson(config)` | Locale.ROOT JSON containing diagnostics mode, hard-coded CLI version/epoch, escaped java.version/os.name (empty defaults) and configIncluded based only on non-null config. No source commit, config digest, database identity or manifest signature. |
| `AetherCli.redactedEnvironment(environment)` | Sorts whole map by key, then emits only case-sensitive AETHER_ names as name=value newline, redacting by name. Other environment names are omitted regardless of content. Null selected values become empty strings; null keys fail sorting. Does not escape property delimiters or embedded newlines. |
| `AetherCli.redactedProperties(config)` | Loads Properties with Files.newBufferedReader, sorts decoded property names and emits name=redactedValue newline. Exports all names, including custom properties. Does not invoke config catalog validation or reproduce file comments/order/duplicate definitions. Raw newline/backslash/delimiter characters are not Properties-escaped on output. |
| `AetherCli.redactIfSensitive(name, value)` | Null value becomes empty string. Otherwise lowercases name with Locale.ROOT; substrings secret, password, token, credential, private, kek or key cause SecretRedactor.redact with diagnostic kind. Other values pass through unchanged. Broad key substring can redact nonsecrets; unrelated names can expose secrets. No value-content scanning or exhaustive credential recognition. |
| `AetherCli.putZipEntry(zip, name, value)` | Creates supplied entry name, sets timestamp zero, writes UTF-8 bytes and closes entry. No name/content validation or atomicity; callers here supply fixed entry names. Stable timestamps alone do not prove fully byte-reproducible archives. |
| `AetherCli.printReleaseCertificationText(manifest, report)` | Prints PASS/FAIL from productionReady, releaseVersion/gitCommit/createdAt, green/not-applicable/red/missing counts, failure lines and warning lines. Renderer does not independently validate report or verify output delivery. |

## Shared Helpers and Explicit Records

| Declaration | Behavior and failure boundary |
| --- | --- |
| `AetherCli.requiredValue(values, optionIndex, option)` | Requires a following token not starting --; returns it unchanged. Assumes a valid option index supplied by caller. Cannot accept -- prefixed values; does not trim or validate semantic type. |
| `AetherCli.configOverrides(values)` | Scans tokens after command/file. Skips --json, accepts repeated --set followed by name=value using first equals sign, rejects unknown options and empty name. Empty value allowed; later same name overwrites earlier. Returns Map.copyOf; whitespace and key validity are delegated to loader. |
| `AetherCli.requireText(value, field)` | Rejects null or isBlank text with field-required message, otherwise returns unchanged without trimming. Shared schema/helper validation, not JSON escaping. |
| `AetherCli.jsonString(value)` | Adds quotes and escapes quote, backslash, backspace, formfeed, newline, carriage return, tab and remaining control chars below U+0020. Leaves other Java chars unchanged, including surrogate code units; no Unicode-normalization/valid-surrogate check. Null input fails. |
| `AetherCli.settingsJson(settings)` | Iterates map order, encodes each key/value with jsonString and joins comma-separated members. Returns object interior without braces. No ordering guarantee beyond supplied map, redaction or null filtering. |
| `AetherCli.DiagnosticsBundleReport.DiagnosticsBundleReport(bundle, entries)` | Compact constructor requires non-null bundle and positive entry count. Does not prove file exists, ZIP validity or entry count matches file contents. Implicit accessors expose stored path/count. |
| `AetherCli.CliCommandSchema.CliCommandSchema(command, jsonMode, exitCodes, jsonFields)` | Requires nonblank command/jsonMode and nonempty lists, copying lists with List.copyOf (null lists/elements fail). Does not constrain exit-code range, duplicates, field syntax or actual command behavior. |
| `AetherCli.CliCommandSchema.toJson()` | Formats command/jsonMode directly into quoted positions, joins integer codes and jsonString-escapes field names. Built-in names are controlled; constructor accepts text that this direct interpolation would not safely escape. Not a general arbitrary-schema serializer. |

Records also receive implicit accessors, equals/hashCode/toString from Java.
The copied schema lists are immutable snapshots, unlike arbitrary mutable caller
lists. These implicit members are not counted among the 20 explicit declarations.

## Redaction and Publication Limits

The diagnostics export and config-validation output use **different redaction
paths**. Config validation uses loader catalog-aware redactedDiagnosticView;
ZIP properties/environment use substring-based redactIfSensitive. Do not assume
they redact the same fields or produce the same representation.

SecretRedactor emits diagnostic-prefixed redaction with the first eight SHA-256
digest bytes in hex, or an empty marker. This is deterministic correlation, not
encryption or a salted password hash: equal secrets correlate and low-entropy
values can be guessed by hashing candidates. A field without a matching sensitive
name remains plaintext. Review bundles before sharing them; neither status 0 nor
the word redacted certifies a secret-free export.

The ZIP is created directly at its final path. Failure during properties loading,
redaction or writing can leave a partial file, and a retry to the same path then
rejects existing output. There is no rollback, durable publication protocol or
guarantee that printed receipt implies stable storage after host failure. Bundle
creation does not acquire a database lock because it collects configuration and
selected process environment, not a consistent database snapshot.

Static schema fields are selected interface descriptions, not full output
validation. For example config-validate lists both settings and error although
success/failure output chooses one. Release command name release-certify maps to
JSON mode release-certification. Warnings/readiness evaluation is owned by the
release evaluator, not the text printer or schema catalog.

## Verification Evidence

Source tests in AetherCliTest assert ZIP entry count/manifest, selected AETHER_
environment filtering, removal of named secret plaintext, preservation of normal
values, rejection of existing bundle without replacing bytes, resolved override
and redacted settings, unsafe production config returning status 2, and full/single
command-schema output plus unknown-command rejection. They were read, not executed
in this documentation batch. They do not establish exhaustive secret detection,
atomic output, arbitrary schema-string escaping or every file-error path.

Compiler-tree documentation checks compare all 20 scoped declaration rows against
source. No user's config, environment bundle, database or release manifest was
exported/evaluated to write this guide.
