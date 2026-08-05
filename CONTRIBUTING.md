# Contributing

Read the [coding standards](Docs/07_Development_Workflow_and_Coding_Standards\(1\).md) and
[build commands](docs/wiki/Build_and_Test_Commands.md) before changing code.

Use focused branches (`feature/`, `fix/`, `docs/`, `perf/`, `build/`) and Conventional Commit
subjects. Describe ownership, lifecycle, failure behavior, and tests for every change. Native
memory and concurrency changes require the corresponding review checklist.

Do not add production dependencies on `aether-testkit`, allocate FFM memory outside
`aether-memory`, or perform raw NIO storage access outside `aether-io`.
