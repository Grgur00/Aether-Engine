# Bulk Layout Regression Functions

[Function index](FUNCTION-INDEX.md) | [Bulk diagnostic drivers](BULK-DIAGNOSTIC-DRIVERS.md) | [Hit-path functions](HIT-PATH-FUNCTIONS.md)

Source: [bulk_layout.py](../../scripts/bulk_layout.py). This reference covers all
**6 explicit declarations**, including the nested packed-read callback. It owns
post-population checks, not SSTable construction or the authoritative verifier.

## Architecture and Measurement Boundaries

```text
bulk_population worker -> bulk writer ready -> start Python/Java RSS sampler
    populate -> finish -> close writer -> stop sampler
new Java daemon -> ordinary reader restart validation
    regressions -> two warmup passes -> five measured packed-read passes
                -> unchanged V0 prefix + identical transform
                -> fetch updated dataset -> strict drain
                -> full tensor digest + cache cardinality
```

The caller starts memory sampling after entering `BulkPipeWriter`, then stops it
after closing that writer. Startup before readiness is not sampled. Restart and
regression checks happen afterward in a fresh Java daemon. The caller reports
these under `validationMs`, outside its V0 `totalMs` (startup + population + close).
None of these checks measures model training or the H2 lifecycle endpoint.

## Memory Sampler

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `MemorySampler.__init__(java_pid)` | Records this Python process and the supplied Java PID; initializes two availability flags, individual RSS maxima and a simultaneous combined maximum. Allocates a stop event and a daemon thread targeting `run`. | Does not start or validate either process. PID validity and snapshot support come from the caller and delegated `base.snapshot`. |
| `MemorySampler.start()` | Starts the preconstructed sampling thread. | One-shot thread lifecycle: calling twice raises Python's thread error. No readiness handshake or first-sample guarantee. |
| `MemorySampler.run()` | Until stopped, snapshots Python then Java, ORs each availability flag, updates each individual maximum and the maximum sum from that polling iteration. Waits on the stop event for 20 ms between iterations. | Samples are sequential, not simultaneous; real period includes snapshot overhead. Missing `rssBytes` contributes zero. Combined peak is not the sum of the individual peaks. A snapshot exception terminates the thread; it is not captured and re-raised to the caller. |
| `MemorySampler.stop()` | Sets the event, joins the thread, and returns individual peaks or `None` where no available sample occurred. Returns a combined peak only if both processes had an available sample at some point, with interval and scope annotations. | Availability need not occur in the same iteration, so the combined value can understate resident memory. Joining an unstarted thread raises. Join has no timeout. These are sampled RSS values, not exact peaks, allocator totals or JFR allocation estimates. |

The sampler borrows PIDs; it does not launch, terminate or own the corresponding
processes. Its stop event interrupts the inter-sample wait but cannot interrupt
an in-progress snapshot. The daemon-thread flag is not a substitute for calling
`stop` in the caller's lifecycle.

## Read and Update Checks

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `regressions(request, sources, identity, daemon, store)` | Derives keys using `monai-pilot-v1`, each source identity, the supplied transform identity and version `1`. Runs warm reads, then loads the trusted update manifest and requires its V0 prefix and transform identity to match. Fetches every updated batch, drains storage, requires exactly `len(updated) - len(sources)` transform calls, checks full readback digest against `updateReferenceHash`, and checks exact cache entry count. Returns separate `warm` and `incremental` reports. | Borrowed daemon/store remain alive. Uses fixed batch 16, two warmup passes and five measured passes. Prefix/order equality is mandatory, not an arbitrary-revision reuse policy. Input/request errors propagate; update work can already have published artifacts when a later check fails. No rollback. |
| `regressions.read(indices)` | Times one `get_many_values` request plus extraction of each packed value, hit/cardinality assertion, byte-length summation and closing the packed response. Returns sample count, returned bytes and duration in nanoseconds. | A missing value aborts via `require_hits`; publication is never attempted here. `packed.close()` runs even if extraction or validation fails, but cannot run if acquisition itself throws. Returned values are consumed before closing their owner. No codec decode, GPU transfer, model, training or prefetch. |

Warmup completes before the initial background-compaction snapshot. That snapshot
must be idle with zero debt/failures and valid activity counters. Each measured
epoch summarizes batch durations and wall elapsed time. The final snapshot must
still be idle with unchanged monitored counters. The combined report sums epoch
elapsed times, excluding gaps between epochs. This is an activity-fenced warm
lookup diagnostic, not a controlled cold-page-cache test.

Update timing starts before dataset construction and ends after the first full
fetch pass. `admissionMs` therefore includes dataset setup, lookup, missing-only
preprocessing and publication. `drainMs` is separate. Java process usage and disk
snapshots span admission plus drain; subsequent digest readback is outside those
snapshots. `diskBefore`/`diskAfter` and process I/O are logical/process measures,
not device-level write amplification.

The updated dataset is closed in `finally` once successfully constructed.
Failures during construction occur before that cleanup block. The warm and final
observer clients have context-managed cleanup; this function never closes the
daemon. Final digest/cardinality checks establish this invocation's expected
readback, not crash consistency or recovery across another process death.

## Verification and Reading Order

Read `bulk_population.run_bulk_case` for sampling and restart ownership, then this module,
then `require_hits`, `require_idle`, `require_no_activity` and `summarize` in the
hit-path guide. Follow the population/adapter references for `workload_args`,
`strict_drain`, dataset fetch, process snapshots and tensor-digest encoding.

The documentation inventory test compares every qualified declaration in this
complete source file with its function table. CPU sampler contract tests exercise
peak aggregation, unavailable samples and stop/start ownership. They do not
establish Java integration, packed transport performance, actual RSS accuracy,
incremental durable publication or GPU behavior. Those require the real runtime
and disposable dataset/store fixtures.
