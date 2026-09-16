# Changelog

## Unreleased

- Validate inline artifact CRC32C/SHA-256 once per immutable resident SSTable
  value, with validation renewed after reload or replacement. Retain storage
  block checks, write hashes, native lookup checks and per-read segment checks.
- Add isolated V2 hit-path diagnostics with strict hit/storage-activity gates,
  Java/RPC/input timing layers, request-size sweeps and correlated read traces.
  Add bounded, ordered prefetch metrics and configurable pilot lookahead depth.
- Pack batched cache reads directly into the RPC response after checksum validation;
  decode Python training artifacts from read-only response views and reuse adapter
  fingerprints/keys. Wire formats and TCP settings are unchanged.
- Split SSTable finalization timing into block encoding/checksums, filter/index
  construction, buffer copies, file write/force/close and full readback validation,
  correlated per output file with its flush or compaction request.
- Add opt-in compaction stage timings to correlated cache request traces,
  including input reads, merge work, output construction, synchronization,
  manifest publication, installation and obsolete-file deletion.
- Export Kaggle results as one verified ZIP with logs, runtime metadata, run
  status and checksums, including partial results on setup/experiment failure.
  Standalone notebooks provide a ZIP export cell; the CLI downloads only the ZIP.
- Reduce training-cache request/response allocations, buffer response framing,
  and enable TCP_NODELAY on accepted TCP/TLS connections.
- Replace per-byte segment filename formatting and repeated crash-point regex
  compilation while preserving existing names and validation behavior.
- Return segment payloads correctly in packed batch reads, including mixed
  inline/segment results, duplicates and misses.
- Add reproducible JFR profiling and comparisons of frozen cache builds with
  balanced ordering and Java process CPU measurements.
- Bootstrap the multi-project repository and engineering workflow.
