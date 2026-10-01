# Streaming Verifier v1

Status: implementation under correctness validation; not benchmark evidence.

The inventory verifier scans DATA entries using bounded key scratch and skips
value bytes after validating the existing block envelope/checksum. Envelope
copying is deliberately unchanged. Ordinary builder verification and user reads
still use SSTableReader. No verification pass or publication barrier is removed.

## Approved corruption rejection differences

On 2026-10-01 the user approved stricter rejection of malformed restart arrays.
The old decoder validates numeric restart bounds/order but accepts some offsets
inside entries and shared prefixes at restart points. The new DATA scanner
requires a zero first restart, actual entry boundaries, and zero shared prefix
at every restart. Tests explicitly demonstrate these old-accept/new-reject cases.
Normal reads are not changed. Valid tables produced by the builder are unaffected.
Malformed internal keys also produce controlled corruption exceptions instead
of leaking the old comparator's range exceptions.

## Measurement plan

Compare against the frozen verification-v2 implementation, not the original
three-pass bulk loader. Five fresh paired runs, 1200 artifacts, batch 16,
32 MiB target, no training and no JFR. Primary: median authoritative inventory
verification time, at least 25% below the paired baseline. The approximate
376 ms reference is contextual, not a replacement for the fresh paired baseline.
Report population wall time as secondary. Preserve the old failed 750 ms
population gate unchanged. New per-table metrics report logical bytes scanned
and requested file-read bytes, not physical-device I/O.

Only after correctness and the unprofiled benchmark pass may envelope-copy
removal be considered as a separately measured v2. No further V0 tuning or
confirmatory campaign is authorized by this implementation alone.
