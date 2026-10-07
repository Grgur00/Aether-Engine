# Aether Engine Documentation Index

Status: REQUIRED

## Start With The Current Implementation

**[Developer onboarding](onboarding/README.md)** is the source-backed entry point
for new contributors. It includes setup commands, architecture and lifecycle
graphs, typed schema workflow, Python/training-cache integration, testing,
operations and research methodology.

The chapter table below is a design/specification inventory, not a release
certification or a substitute for tracing the current implementation. A chapter
can specify more than its corresponding module currently integrates. Consult
the onboarding guides and linked source/tests for executable behavior.

| Number | Title | Status | Implementation state | Dependencies | Supplements/supersedes |
|---|---|---|---|---|---|
| Roadmap | Remaining Documentation Roadmap | Current | Mixed | repository inspection | supersedes root `ROADMAP.md` for production docs planning |
| 03-26 | Historical implementation specifications | Missing from checkout | Unknown | unavailable | must be restored or reconciled later |
| 16A-23C | Historical supplements | Missing from checkout | Unknown | unavailable | website docs cover some API/schema/workbench topics |
| 27 | Encryption, Authentication, Authorization and Secrets Management | Authored | SPECIFIED_NOT_IMPLEMENTED | RPC, cluster identity, storage | new |
| 28 | Observability, Metrics, Logging and Distributed Tracing | Authored | SPECIFIED_PARTIALLY_IMPLEMENTED | 27, all subsystems | new |
| 29 | Reliability, Chaos Testing and Consistency Verification | Authored | SPECIFIED_PARTIALLY_IMPLEMENTED | 27, 28, storage, Raft | new |
| 30 | Performance Engineering, Benchmark Methodology and Regression Gates | Authored | SPECIFIED_PARTIALLY_IMPLEMENTED | 28, 29, 31 | unifies benchmark supplements |
| 31 | Capacity Planning, Resource Limits and Overload Control | Authored | SPECIFIED_PARTIALLY_IMPLEMENTED | 28, 30 | new |
| 32 | Production Configuration and Configuration Management | Authored | NOT_YET_DESIGNED | 27-31 | new |
| 33 | Kubernetes Stateful Deployment and Operator | Authored | NOT_YET_DESIGNED | 27-32, Raft membership | new |
| 34 | Multi-Zone and Failure-Domain Deployment | Authored | NOT_YET_DESIGNED | 31, 33, Raft | new |
| 35 | Administration CLI and Operational Runbooks | Authored | IMPLEMENTED_NOT_DOCUMENTED plus gaps | 27-34 | extends local CLI |
| 36 | Backup, Restore and Disaster-Recovery Certification | Authored | SPECIFIED_PARTIALLY_IMPLEMENTED | 27, 29, 33, 35, 39 | extends checkpoint tooling |
| 37 | Client SDK, Connection Management and Application Integration | Authored | SPECIFIED_PARTIALLY_IMPLEMENTED | API, RPC, 27, 28, 31, 32 | new |
| 38 | Production Readiness and Release Certification | Authored | SPECIFIED_PARTIALLY_IMPLEMENTED | all chapters | new release gate |
| 39 | Persisted Format Catalog and Compatibility Registry | Authored | IMPLEMENTED_NOT_DOCUMENTED | storage, security, release | authoritative durable-byte catalog plan |
| 40 | AI Training-Data Cache Implementation Proposal | Historical proposal | Experimental implementation now present | 27-39, local NVMe cache, benchmark harness | see current [training-cache guide](onboarding/TRAINING-CACHE-AND-PYTHON.md); proposal is not a statement of production readiness |

## Current Source-Backed Supplements

- [Onboarding index](onboarding/README.md): role-based reading paths and a practical first week.
- [Architecture](onboarding/ARCHITECTURE.md): module inventory and implemented runtime boundaries.
- [Module guide](onboarding/MODULE-GUIDE.md): all 49 Java modules, entry symbols and editing boundaries.
- [Guided code tour](onboarding/CODE-TOUR.md): application-to-engine call chains, failure semantics and first-contribution exercises.
- [Storage engine](onboarding/STORAGE-ENGINE.md): write/read, flush, compaction, recovery and ownership paths.
- [Typed API and schemas](onboarding/TYPED-API-AND-SCHEMAS.md): collections, generated codecs and schema evolution.
- [Training cache and Python](onboarding/TRAINING-CACHE-AND-PYTHON.md): artifact identity, batching, durability and integration.
- [Testing and contributing](onboarding/TESTING-AND-CONTRIBUTING.md): actual task coverage and review expectations.
- [Operations and debugging](onboarding/OPERATIONS-AND-DEBUGGING.md): configuration, safe offline tools and troubleshooting.
- [Experiments and profiling](onboarding/EXPERIMENTS-AND-PROFILING.md): workload scopes, provenance, research roles and JFR.
- [H2 training workload](onboarding/H2-TRAINING-WORKLOAD.md): segmentation model, frozen storage/harness boundaries and five-block persistent lifecycle endpoint.
- [Root README](../README.md): developer overview, quick starts, limitations and benchmark narrative.
- [Website documentation](../website/docs/index.html): API, schema workflow, durability modes, lifecycle, metrics and limits.
- [RELEASING.md](../RELEASING.md): publishing process.
- [SECURITY.md](../SECURITY.md): vulnerability reporting.
- [Benchmark README](../benchmarks/README.md): benchmark entry point.

## Out-of-Scope for Distributed V1

Automatic sharding, multi-shard transactions, cross-region replication, distributed transactions, CDC/watch streams, TTL, secondary indexes, multi-tenancy/quotas, and blob-file storage are POST_V1 unless promoted through a new roadmap.
