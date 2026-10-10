# H2 Multi-Session Amendment

Authorized on 2026-10-10 after nine completed paired blocks, before collecting
blocks 10-24. This is an explicit departure from the original same-host-only
continuation policy, not a retrospective claim that the original protocol
allowed cross-host pooling. No interim significance test or performance-based
selection is used to decide whether to continue.

## Unchanged Treatment

The original frozen V4 bundle, candidate source, worker harness, Java/Python and
package versions, input hashes, backend orders, seeds, 24-block sample size,
timing boundary and primary paired log-ratio analysis remain unchanged.
V0 uses bulk-streaming-v1, a 32 MiB SSTable target, batch 16 and the single
authoritative streaming verifier. Each paired block contains four complete
backend lifecycles; the Aether daemon remains alive across V0-V4 within that
block, never across blocks or sessions. Training remains 20 epochs per version.

## Session Allocation

| Session | Human Block IDs | Count |
| --- | --- | --- |
| 1, completed | 1-9 | 9 |
| 2, next launch | 10-18 | 9 |
| 3, final launch | 19-24 | 6 |

The controller runs the original scheduled assignments, not fresh block IDs or
new permutations. Each new session runs an excluded four-backend preflight,
checks deterministic input identities and exact runtime versions, and records
its own host environment. Original receipts are preserved byte-for-byte and
validated against their original environment, never relabeled as the new host.
Missing, overlapping, altered or out-of-range blocks are rejected. Interrupted
lifecycles remain excluded; retry the entire four-backend block under the
original three-attempt limit. Partial lifecycles cannot cross sessions.

`scripts/h2_sessions.py` is a separately hashed controller layered over the
original frozen harness. The initial implementation authorizes session 2 only;
session 3 and final aggregation must validate all three session inventories
before permitting inference. The original `--resume` host fence is unchanged.

## Reporting

Until all 24 scheduled blocks are valid, report counts and correctness only.
The final report must disclose this amendment, the fixed session ranges, each
environment identity and a session-level descriptive sensitivity summary.
The original pooled paired log-ratio endpoint is retained, but interpretation
must acknowledge possible session/host effects and clustered observations.
This is an amended confirmatory campaign, not an unchanged preregistered
single-host experiment. No unsupported single-host or perfectly controlled
page-cache claim should be made.
