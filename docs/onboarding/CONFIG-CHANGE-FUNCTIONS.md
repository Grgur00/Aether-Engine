# Configuration Reload and Cluster Policy Functions

[Function index](FUNCTION-INDEX.md) | [Loading and validation](CONFIG-LOADING-FUNCTIONS.md) | [Operations](OPERATIONS-AND-DEBUGGING.md)

This reference covers the other ten implementation files in `aether-config`:
reload evaluation, synchronized configuration state, reload result models,
cluster compatibility checks, and their proposal/member/report models.
Together with the loading reference it covers the module's explicit production
function declarations, excluding `package-info`. Policy acceptance is not evidence
that the storage engine, RPC transport, or Raft scheduler changed at runtime.

## Architecture and Ownership

| Component | State and role | What it does not do |
| --- | --- | --- |
| `AetherConfigHotReloadManager` | Retains registry/validator; evaluates two immutable configurations | Does not own or modify the current configuration |
| `AetherConfigState` | Owns one configuration reference; serializes reads/reloads | Does not notify runtime consumers or rebuild their configurations |
| `ClusterConfigCompatibilityChecker` | Retains registry/validator; checks supplied member snapshots | Does not discover membership, contact nodes, vote, or commit a change |
| Proposal and report records | Immutable values describing a transition and its result | Do not prove operator approval, authenticated provenance, or rollout completion |

Production-source search currently finds no consumers of these owners/checkers
outside `aether-config`; tests exercise them directly. Treat them as available
policy mechanisms, not automatically wired administration endpoints.

## Hot Reload Evaluation

Source: [AetherConfigHotReloadManager.java](../../modules/aether-config/src/main/java/io/aetherdb/config/AetherConfigHotReloadManager.java).

### Constructor and defaults

`AetherConfigHotReloadManager(registry)` requires the registry and creates its
validator. `defaults()` constructs a manager with a new default registry.
The manager does not retain either configuration passed to `evaluate`.

### evaluate

`evaluate(current, proposed)` requires both inputs, then validates current and
proposed in that order. A `ConfigValidationException` returns a rejected decision
with no changes and one error message. An invalid current configuration therefore
cannot be repaired through this evaluation path: its failure stops evaluation
before the proposed configuration is validated. Other exception types propagate.

For valid inputs, it iterates every registered definition and compares the two
effective values using exact `String.equals`. Omitted values use registry defaults.
Equivalent parsed values with different spellings, such as `true` and `TRUE` or
`2` and `02`, count as changes. An explicit value equal to an omitted default does
not count as a change.

Each changed setting is eligible only when `hotReloadable` is true and
`restartRequired` is false. Scope does not add a cluster agreement check here.
Changed values are redacted for diagnostics when required, and the decision is
accepted only if all changed settings are eligible. Valid identical configurations
produce an accepted decision with no changes.

The decision's `ConfigReloadChange.applied` field means eligibility in this
evaluator, not a completed runtime mutation. A rejected mixed proposal can contain
both eligible and denied changes. Nothing has been partially applied by the manager.
Change ordering follows registry iteration and is not a stable presentation contract.

### Private Helpers

| Function | Behavior |
| --- | --- |
| `deniedReason(setting)` | Returns `restart required` when that flag is set; otherwise `setting is not hot reloadable`. Restart takes precedence when both flags prevent reload. |
| `diagnosticValue(setting, value)` | Returns `REDACTED` for a nonblank sensitive value; otherwise returns the original value. Comparisons use raw values before redaction. |

## Reload Result Models

### ConfigReloadChange

Source: [ConfigReloadChange.java](../../modules/aether-config/src/main/java/io/aetherdb/config/ConfigReloadChange.java).

`ConfigReloadChange(name, previousValue, nextValue, applied, reason)` requires a
nonblank name and non-null values/reason. A denied change requires a nonblank
reason. It does not validate registry membership, require previous/next values to
differ, enforce redaction, or require an empty reason for an eligible change.
Implicit record accessors return these immutable strings and flags.

### ConfigReloadDecision

Source: [ConfigReloadDecision.java](../../modules/aether-config/src/main/java/io/aetherdb/config/ConfigReloadDecision.java).

| Function | Behavior |
| --- | --- |
| `ConfigReloadDecision(accepted, changes, errors)` | Copies both non-null lists, rejecting null elements. Requires `accepted` to equal: no denied change and no error. Does not require unique setting names. |
| `appliedChanges()` | Returns a filtered, unmodifiable list of entries whose eligibility flag is true, even if the overall decision is rejected. |
| `deniedChanges()` | Returns a filtered, unmodifiable list of entries whose eligibility flag is false. |
| Implicit `accepted()`, `changes()`, `errors()` | Expose the result and immutable copied lists; no side effects. |

Do not use `appliedChanges()` alone as evidence that an `AetherConfigState` accepted
a mixed proposal or that a runtime subsystem reloaded any of its settings.

## Synchronized Configuration State

Source: [AetherConfigState.java](../../modules/aether-config/src/main/java/io/aetherdb/config/AetherConfigState.java).

| Function | Behavior |
| --- | --- |
| `AetherConfigState(initial, registry)` | Requires initial/registry, constructs a manager, and evaluates initial against itself. Rejects invalid initial state with the first error or a fallback message. Restart-only settings do not prevent construction because no value changes. |
| Synchronized `current()` | Returns the current immutable configuration reference while holding this object's monitor. No map copy is needed. |
| Synchronized `reload(proposed)` | Evaluates the entire replacement while holding the same monitor; replaces `current` only if accepted; returns the decision either way. Invalid or denied proposals preserve the previous reference. |
| `defaults(initial)` | Constructs a state owner using the default registry; it does not invent an initial configuration. |

Reload accepts a **complete proposed configuration**, not a patch. An omitted
explicit setting reverts to its registry default during comparison; it does not
inherit the old explicit value. The accepted configuration object is installed
as supplied, so explicitly storing a default can change `values()` even when the
decision has no effective changes.

The monitor makes configuration-reference replacement atomic relative to this
owner's `current` and `reload` methods. It does not coordinate databases, update
other cached runtime objects, publish a cluster log entry, or persist the new map.

## Cluster Snapshot and Proposal Models

| Source and constructor | Checks | Remaining caller responsibility |
| --- | --- | --- |
| [ClusterConfigMember](../../modules/aether-config/src/main/java/io/aetherdb/config/ClusterConfigMember.java): `ClusterConfigMember(nodeId, configuration, supportedCompatibilityEpoch)` | Nonblank node ID, non-null configuration, epoch >= 1 | Configuration validity, real voting membership, freshness, and unique node IDs |
| [ClusterConfigSettingChange](../../modules/aether-config/src/main/java/io/aetherdb/config/ClusterConfigSettingChange.java): `ClusterConfigSettingChange(settingName, fromValue, toValue)` | Nonblank name, non-null values, exact old/new strings must differ | Registered cluster scope and endpoint semantic validity |
| [ClusterConfigChangeProposal](../../modules/aether-config/src/main/java/io/aetherdb/config/ClusterConfigChangeProposal.java): `ClusterConfigChangeProposal(proposalId, changes)` | Nonblank ID, non-null copied nonempty list, no duplicate setting names | Approval/authentication, durable proposal identity, rollout execution |

`ClusterConfigChangeProposal.settingNames()` builds a new immutable set of the
change names. Set iteration order is not promised. The checker does not use
`proposalId` to deduplicate, authenticate, or recover a rollout.
Implicit accessors expose immutable records and copied collections. None of these
constructors looks up the registry or starts global configuration validation.

## Cluster Compatibility Checker

Source: [ClusterConfigCompatibilityChecker.java](../../modules/aether-config/src/main/java/io/aetherdb/config/ClusterConfigCompatibilityChecker.java).

`ClusterConfigCompatibilityChecker(registry)` requires the registry and creates
its validator. `defaults()` supplies a newly built default registry.

### checkVotingMembers

`checkVotingMembers(members, stagedSettings)` copies the non-null list and set,
rejects null elements, and requires at least one supplied member. The caller
decides who is a voter; duplicate IDs and missing real voters are not detected.

Its phases are:

1. Emit `INVALID_STAGED_SETTING` for each staged name not registered or not
   `CLUSTER_WIDE`.
2. Validate every member's entire configuration. A validation exception becomes
   one `CONFIG_VALIDATION_FAILED` issue for that node. Continue checking other
   members and cluster comparisons even after these issues.
3. For every cluster-wide definition, check every member's supported epoch
   against the definition's epoch. Emit `UNSUPPORTED_COMPATIBILITY_EPOCH` when
   too low. Staging does not bypass this check.
4. Unless the definition is staged, compare every subsequent member's effective
   value with the first member's value using exact strings. Emit
   `CLUSTER_SETTING_MISMATCH` for each different member.
5. Return a report with compatible = no issues, the supplied list size, and
   alphabetically sorted staged names (including invalid staged names).

Node-local settings may differ across members but must still pass their own
configuration validation. Staging in this method only suppresses equality checks:
it does not require an old/new proposal or prove an active transition. All current
registry epochs are 1 and member construction requires epoch >= 1, so the
unsupported-epoch branch needs future higher setting epochs to become reachable
through the present default registry.

### checkStagedChange

`checkStagedChange(members, proposal)` copies/requires inputs and calls
`checkVotingMembers` with the proposal's setting names. It retains those base
issues, then examines each registered cluster-wide change:

- Every member's effective string must exactly equal the proposal's old or new
  value, otherwise emit `STAGED_VALUE_OUTSIDE_PROPOSAL` for that member.
- At least one member must have the old value and at least one the new value,
  otherwise emit `STAGED_CHANGE_NOT_IN_PROGRESS` for that setting.

Unknown/node-local changes are skipped in this second phase, but already produce
base invalid-staged issues. Endpoint strings are not separately validated as
hypothetical configurations; actual member configurations receive validation.
The report is compatible only if both phases produced no issues.

This check represents the **mixed transition phase**. Uniform all-old and uniform
all-new states fail its in-progress rule, even though an unstaged ordinary
compatibility check could accept them. A single supplied member cannot satisfy
both distinct endpoint values. There is no quorum calculation or proposal commit.

## Cluster Reports and Issues

| Source and function | Behavior |
| --- | --- |
| [ClusterConfigCompatibilityIssue](../../modules/aether-config/src/main/java/io/aetherdb/config/ClusterConfigCompatibilityIssue.java): constructor | Requires nonblank code and non-null setting name, node ID and detail; empty setting/node strings are allowed for general issues. Does not restrict codes to the checker's constants. |
| [ClusterConfigCompatibilityReport](../../modules/aether-config/src/main/java/io/aetherdb/config/ClusterConfigCompatibilityReport.java): constructor | Requires a positive voting-member count; copies staged/issue lists; requires compatible to equal issue-list emptiness. Does not deduplicate or sort arbitrary caller-supplied lists. |
| Implicit record accessors | Return immutable fields/lists; do not rerun checks. |

The checker emits diagnostics containing names, node IDs, and explanations, not
raw old/new values. Issue order follows phase and input/registry iteration; only
the returned staged-name list is explicitly sorted.

## Tests and Coverage Limits

[AetherConfigHotReloadManagerTest](../../modules/aether-config/src/test/java/io/aetherdb/config/AetherConfigHotReloadManagerTest.java)
checks eligible changes, restart denial, sensitive-value redaction and invalid
proposal rejection.
[AetherConfigStateTest](../../modules/aether-config/src/test/java/io/aetherdb/config/AetherConfigStateTest.java)
checks accepted replacement and preservation after denied/invalid changes; its
sequential examples do not establish concurrent runtime-consumer integration.
[ClusterConfigCompatibilityCheckerTest](../../modules/aether-config/src/test/java/io/aetherdb/config/ClusterConfigCompatibilityCheckerTest.java)
checks matching/mismatched members, staging, old/new endpoints, outside values,
uniform-transition rejection, invalid scope/configuration and epoch construction.

The declaration inventory checks function names in all ten files. Source review
explains their contracts; these checks do not demonstrate a deployed admin API,
live transport reconfiguration, authenticated voting snapshots, or Raft-backed
configuration rollout.
