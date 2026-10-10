# Release Certification Functions

[Function index](FUNCTION-INDEX.md) | [Module guide](MODULE-GUIDE.md) | [Operations](OPERATIONS-AND-DEBUGGING.md)

Source: [aether-release](../../modules/aether-release/src/main/java/io/aetherdb/release).
This reference covers **22 explicit declarations across all nine implementation
files**: six classes/records and three enums. Generated record/enum members and
package-info are excluded from declaration counts.

## Architecture and Trust Boundary

```text
Operator evidence + blocker declarations + release identity
    -> ReleaseCertificationManifest.of / constructor
    -> ReleaseCertificationManifestCodec.encode -> properties text
    -> caller writes artifact

CLI release-certify -> read text -> decode -> evaluate
    -> report -> JSON or text -> exit 0 (ready) / 2 (not ready)
```

The [CLI consumer](../../modules/aether-tools/src/main/java/io/aetherdb/tools/AetherCli.java)
reads the manifest using Files.readString and maps productionReady to exit status.
The release module itself performs no file IO, artifact downloads, test execution,
signature verification or publication. Its readiness result means the supplied
declarations satisfy the evaluator rules, not that the checkout is production-ready.

## Evidence Vocabulary

Sources: [CertificationArea.java](../../modules/aether-release/src/main/java/io/aetherdb/release/CertificationArea.java),
[EvidenceStatus.java](../../modules/aether-release/src/main/java/io/aetherdb/release/EvidenceStatus.java),
[ReleaseBlockerSeverity.java](../../modules/aether-release/src/main/java/io/aetherdb/release/ReleaseBlockerSeverity.java).

CertificationArea defines 13 required rows: CORRECTNESS, DURABILITY, RECOVERY, RAFT,
SECURITY, PERFORMANCE, RESOURCE_LIMITS, COMPATIBILITY, BACKUP_RESTORE, KUBERNETES,
OBSERVABILITY, RELEASE_PROVENANCE and DOCUMENTATION. Every area must be present;
NOT_APPLICABLE with rationale can satisfy an area, including RAFT or SECURITY.
EvidenceStatus is GREEN, RED or NOT_APPLICABLE. Severity is WARNING or BLOCKER.
These enums do not discover tests or enforce a predefined list of release rules.

## Evidence and Blocker Functions

Sources: [ReleaseEvidence.java](../../modules/aether-release/src/main/java/io/aetherdb/release/ReleaseEvidence.java),
[ReleaseBlocker.java](../../modules/aether-release/src/main/java/io/aetherdb/release/ReleaseBlocker.java).

| Function | Behavior | Validation and limitations |
| --- | --- | --- |
| `ReleaseEvidence.ReleaseEvidence(area, status, summary, artifactUris, nonApplicabilityRationale)` | Requires area/status and nonblank summary; copies artifact list. Null rationale becomes empty, otherwise trim. GREEN requires at least one URI; NOT_APPLICABLE requires nonblank rationale. | Null required references/list elements throw NullPointerException; missing summary or status-specific requirement throws IllegalArgumentException. RED needs no artifacts. No URI scheme/existence/hash policy, text length cap or evidence approval process. |
| `ReleaseEvidence.requireText(value, field)` | Rejects null/blank with field-specific IllegalArgumentException, otherwise returns original string. | Does not strip summary, bound length or reject controls. |
| `ReleaseBlocker.ReleaseBlocker(code, description, severity, resolved)` | Requires nonblank code/description and nonnull severity. Stores resolved flag as supplied. | Text unchanged and unbounded; no known-code registry or duplicate-code check. No proof that resolved=true corresponds to a fix. |
| `ReleaseBlocker.blocking()` | Package-private: true only for unresolved BLOCKER. | Unresolved WARNING does not block; resolved BLOCKER is ignored by evaluator. |
| `ReleaseBlocker.requireText(value, field)` | Rejects null/blank, retains original string. | No trimming, control filtering or maximum length. |

Record accessors expose immutable URI/text values and copied lists. Artifact
presence means a nonempty list, not a reachable or successful report. Rationale
text is required for non-applicability, but the evaluator does not approve its
substance or limit which areas may be marked non-applicable.

## Manifest Ownership and Assembly

Source: [ReleaseCertificationManifest.java](../../modules/aether-release/src/main/java/io/aetherdb/release/ReleaseCertificationManifest.java).

| Function | Behavior | Failure and ownership |
| --- | --- | --- |
| `ReleaseCertificationManifest.ReleaseCertificationManifest(...)` | Requires nonblank releaseVersion/gitCommit and nonnull createdAt/evidence. Copies evidence through EnumMap, requiring map key identical to row.area. Copies blockers and release-level artifact URIs. | Does not require all areas, any release-level artifact, valid version/hash syntax or recent timestamp. Null rows/keys can fail on dereference or EnumMap insertion; mismatched nonnull key throws IllegalArgumentException. Lists reject null elements. Map.copyOf does not promise enum iteration order. |
| `ReleaseCertificationManifest.of(releaseVersion, gitCommit, createdAt, evidence, blockers, artifactUris)` | Converts evidence list into EnumMap, rejecting second row for an existing area, then delegates to constructor. | Null evidence list/row causes NullPointerException. Duplicate blockers/artifacts are retained. No evidence fetching or evaluation. |
| `ReleaseCertificationManifest.requireText(value, field)` | Rejects null/blank identity string, returns unchanged text. | Does not strip, cap or verify exact source identity. |

The manifest is immutable after construction. Missing rows are legal model input
and become evaluator failures. The creation Instant is caller-supplied metadata,
not an automatically captured or authenticated certification time.

## Evaluation and Report Functions

Sources: [ReleaseCertificationEvaluator.java](../../modules/aether-release/src/main/java/io/aetherdb/release/ReleaseCertificationEvaluator.java),
[ReleaseCertificationReport.java](../../modules/aether-release/src/main/java/io/aetherdb/release/ReleaseCertificationReport.java).

| Function | Behavior | Limits |
| --- | --- | --- |
| `ReleaseCertificationEvaluator.ReleaseCertificationEvaluator()` | Private utility constructor. | No mutable certification state. |
| `ReleaseCertificationEvaluator.evaluate(manifest)` | Starts with all areas missing. Iterates declared rows, removes each area, counts GREEN/NOT_APPLICABLE/RED; RED adds failure. Adds failures for remaining areas, then unresolved BLOCKER failures and unresolved WARNING warnings. Returns productionReady exactly when failures empty. | Null manifest causes NullPointerException. Does not open evidence URIs, inspect summary/rationale, compare commit to source or require built-in blockers. RED ordering follows immutable map iteration, not a guaranteed canonical order. Missing areas follow enum order; blockers follow list order. |
| `ReleaseCertificationReport.ReleaseCertificationReport(...)` | Copies required failure/warning lists; requires productionReady == failures.isEmpty and all four counters nonnegative. | Null lists/elements fail with NullPointerException. Does not require counters sum to 13 or agree with failure messages. Direct callers can construct reports unrelated to any manifest. Warnings never force productionReady=false. |
| `ReleaseCertificationEvaluator.toJson(manifest, report)` | Formats compact JSON with mode, readiness, releaseVersion, gitCommit, createdAt, four row counts, release-level artifactUris, failures and warnings. Locale.ROOT numeric formatting. | Does not reevaluate or bind report to manifest: mismatched supplied pair can serialize. Omits evidence rows, their URIs/summaries/rationales and blocker descriptions. No schema version, file write or trailing newline. |
| `ReleaseCertificationEvaluator.jsonString(value)` | Quotes text, escapes quote/backslash, backspace/form feed/LF/CR/tab, and other chars below U+0020 as four-digit Unicode escapes. | Preserves other UTF-16 units, including unpaired surrogates; no Unicode validity check. Null input fails. |

All 13 rows can be NOT_APPLICABLE with rationale and the evaluator can still
return ready if no unresolved blockers exist. This follows declared-status rules;
it is not a claim that every readiness domain has positive test evidence. Likewise,
no blockers listed does not mean the system has discovered that no blockers exist.

## Properties Codec Functions

Source: [ReleaseCertificationManifestCodec.java](../../modules/aether-release/src/main/java/io/aetherdb/release/ReleaseCertificationManifestCodec.java).

| Function | Behavior | Parser/format policy |
| --- | --- | --- |
| `ReleaseCertificationManifestCodec.ReleaseCertificationManifestCodec()` | Private utility constructor. | No IO resources retained. |
| `ReleaseCertificationManifestCodec.encode(manifest)` | Builds Properties for release.version, git.commit, created.at, release artifact count/indexed URIs, each present evidence area's status/summary/rationale/artifact count/indexed URIs, blocker count/indexed code/description/severity/resolved. Delegates to sorted writer. | No schema/version property or integrity digest. Enum names appear literally. List order retained through numeric indices, although lexical key sorting puts index 10 before 2. |
| `ReleaseCertificationManifestCodec.decode(encoded)` | Uses Properties.load(StringReader). For each known area, absent status skips row; otherwise parses enum, summary, URI list and optional rationale. Parses blockers, identity, Instant and release artifact list; delegates to manifest.of. | Duplicate property keys use Properties last-value behavior. Unknown keys/areas ignored. Negative list counts yield empty loops, not rejection. Boolean.parseBoolean treats only case-insensitive true as true; arbitrary other text becomes false. No count/input-size cap. Null text and malformed escapes fail; enum/number/URI/date errors propagate. |
| `ReleaseCertificationManifestCodec.uris(properties, prefix)` | Parses prefix+count, then URI.create for each required indexed uri field. | Syntax validation only, no scheme/absoluteness/existence checks. Negative count returns empty list. Large positive count can drive long parsing/allocation before missing-field failure. |
| `ReleaseCertificationManifestCodec.intProperty(properties, key)` | required lookup then Integer.parseInt. | Does not enforce nonnegative or maximum value. NumberFormatException propagates. |
| `ReleaseCertificationManifestCodec.required(properties, key)` | Returns value if present; otherwise IllegalArgumentException naming missing property. | Blank values remain for downstream validation; keys/values follow Properties parsing. |
| `ReleaseCertificationManifestCodec.storeDeterministic(properties)` | Sorts string property names naturally and writes each through writeProperty into StringWriter; returns text without Properties.store timestamp/comment. | Deterministic ordinary encoding is not canonical-input enforcement or authentication. |
| `ReleaseCertificationManifestCodec.writeProperty(writer, key, value)` | Writes escaped key, equals, escaped value and LF. Converts IOException to AssertionError. | StringWriter use does not normally throw IOException; helper does not flush/close a caller writer. |
| `ReleaseCertificationManifestCodec.escape(value)` | Escapes backslash, LF, CR, tab, equals, colon, hash and exclamation; other characters emitted literally. | Does not escape leading spaces. Properties.load skips leading value whitespace, so accepted strings with leading spaces may not round-trip. No byte encoding here: String input/output, with UTF-8 chosen by caller file IO. |

Evidence keys are `evidence.AREA.status`, `.summary`, `.rationale`,
`.artifact.count` and `.artifact.i.uri`. Release artifacts use `artifact.count`
and `artifact.i.uri`; blockers use `blocker.count` and `blocker.i.*`.
An evidence area lacking status is absent even if its other fields exist. Counts
determine which indexed keys are consumed; extra keys are ignored. Standard
Properties comments, separators, continuations and Unicode escapes are accepted,
not just the encoder's preferred representation.

## Tests and Verification Scope

[ReleaseCertificationEvaluatorTest](../../modules/aether-release/src/test/java/io/aetherdb/release/ReleaseCertificationEvaluatorTest.java)
contains eight tests: complete green rows, missing/red failures, unresolved blocker,
warning, non-applicability rationale/counting, duplicate evidence area rejection,
JSON field substrings and ordinary deterministic codec round-trip.
The JSON test's quoted blocker description is not emitted by toJson, so it does not
actually exercise quote escaping through that description.

These tests do not prove URI reachability, genuine evidence, all-non-applicable
policy safety, report/manifest binding, strict boolean/count parsing, leading-space
round-trip, malformed Properties behavior or exhaustive JSON/Unicode validity.
The CLI has separate consumer tests; module tests alone do not certify packaging,
publishing, artifact signatures or release readiness.
