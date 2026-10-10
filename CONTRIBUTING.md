# Contributing

Start with [Developer onboarding](Docs/onboarding/README.md). Read
[Getting started](Docs/onboarding/GETTING-STARTED.md), the
[architecture map](Docs/onboarding/ARCHITECTURE.md), and
[Testing and contributing](Docs/onboarding/TESTING-AND-CONTRIBUTING.md) before
changing code. These guides describe the current implementation and the actual
test gates, rather than treating historical specifications as completed features.

The [guided code tour](Docs/onboarding/CODE-TOUR.md) follows concrete application,
write/read and recovery call chains. The [module guide](Docs/onboarding/MODULE-GUIDE.md)
identifies entry symbols and ownership boundaries for all Java modules.

Use focused branches (`feature/`, `fix/`, `docs/`, `perf/`, `build/`) and Conventional Commit
subjects. Describe ownership, lifecycle, failure behavior, and tests for every change. Native
memory and concurrency changes require explicit lifetime, synchronization and
failure-path review; use the
[review checklist](Docs/onboarding/TESTING-AND-CONTRIBUTING.md#review-checklist).

Do not add production dependencies on `aether-testkit`, allocate FFM memory outside
`aether-memory`, or perform raw NIO storage access outside `aether-io`.

Preserve unrelated worktree changes. Use disposable stores for tests, review
schema-lock changes explicitly, and document skipped or unrun checks. Research
changes must preserve provenance and keep exploratory and confirmatory evidence
separate; see [Experiments and profiling](Docs/onboarding/EXPERIMENTS-AND-PROFILING.md).
