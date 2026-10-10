# Experimental Results Draft

## Twenty-Epoch Append-Only Confirmation

Under the preregistered append-only OCT5K protocol, V1 contained 1,430 samples and
V2 contained 1,505 samples, including 75 additions and 1,430 unchanged samples
(95.0166% reuse). Twenty training epochs, batch size 16, no prefetch, and disabled
server tracing were fixed before collecting 24 new paired blocks. Backend order
was randomized within each block. Pilot measurements were not pooled with the
confirmatory campaign.

The primary paired log-ratio superiority analysis found an Aether/incremental-mmap
effective-throughput ratio of **1.02691** (two-sided 95% CI **1.01972-1.03416**;
one-sided superiority p = **3.20e-8**). Thus Aether's geometric-mean throughput was
2.69% higher under this particular workload and timing protocol. The one-sided
95% lower confidence bound was 1.02095.

The secondary equivalence test with predefined bounds 0.97-1.03 **did not pass**:
the 90% CI was 1.02095-1.03291 and the TOST p-value was 0.1932. Superiority and
equivalence are different questions; a superiority result must not be described
as simultaneously establishing the secondary equivalence claim.

The secondary comparison with raw recomputation gave a ratio of 2.30238
(95% CI 2.28693-2.31793). RAM-ready remained a reference rather than a hypothesis.
All 24 blocks must retain their manifest, tensor/model correctness, reuse,
source-provenance, and report-checksum validation when reproducing this analysis.

Source: commit `ee42867cf37299662894223be5a3635d003a833c`, tag
`aether-paper-20ep-superiority-v1`. The checksummed result receipt is
`paper/evidence/20ep-superiority-v1.json`; raw results and the exact source ZIP
are preserved locally under `build/preserved-campaigns/20ep-superiority-v1`.

## Earlier Ten-Epoch Protocol

Keep the original 10-epoch equivalence question separate. Its archived protocol
uses 1,170 -> 1,505 samples, 1,003 reusable and 502 new/changed samples, source
`d0db18b49c6adbd531409b6f5f46ca83972071a7`, tag `aether-paper-v1`.

**A completed confirmatory result for that protocol has not been located.** The
available freeze receipt says `uploaded: false` and `campaignLaunched: false`.
Do not turn the earlier pilot CI or a checkpoint from the 20-epoch campaign into
a claimed independent 10-epoch confirmation. Fill this section only after the
corresponding raw campaign and its provenance are recovered and validated.

## Limitations And Next Comparison

These findings concern one small real dataset, one append-only update, the
tested GPU environment, and the implemented incremental mmap baseline. They do
not establish general superiority to mature ML caches, different preprocessing
costs, cold page caches, corrected labels, repeated updates, or other hardware.
The historical effective-throughput endpoint is not a complete accounting of
initial cache population and every service-startup/background-work cost.

The next **exploratory**, separately versioned pilot compares the existing Aether
adapter and incremental mmap with MONAI PersistentDataset and LMDBDataset. It
records initial population, V2 preparation, update-and-20-epoch retraining, and
combined lifecycle costs separately. Native serialization, indexing, batching,
and durability differences are reported explicitly; tensor semantics and model
results must match. No MONAI comparative result is available yet. See
`kaggle/MONAI-PILOT.md` for the fixed five-block pilot protocol.
