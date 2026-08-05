import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "aether-engine"
includeBuild("build-logic")

include(
    ":modules:aether-api",
    ":modules:aether-memory",
    ":modules:aether-format",
    ":modules:aether-io",
    ":modules:aether-memtable",
    ":modules:aether-wal",
    ":modules:aether-sstable",
    ":modules:aether-cache",
    ":modules:aether-lsm",
    ":modules:aether-engine",
    ":modules:aether-testkit",
    ":modules:aether-concurrency-tests",
    ":modules:aether-benchmarks",
    ":modules:aether-tools",
    ":modules:aether-workbench",
    ":modules:aether-rpc-api",
    ":modules:aether-rpc-codec",
    ":modules:aether-rpc-transport",
    ":modules:aether-rpc-testkit",
    ":modules:aether-replication-api",
    ":modules:aether-replicated-log",
    ":modules:aether-state-machine",
    ":modules:aether-replication-testkit",
    ":modules:aether-raft-api",
    ":modules:aether-raft-core",
    ":modules:aether-raft-storage",
    ":modules:aether-raft-testkit",
    ":modules:aether-client-api",
    ":modules:aether-client-codec",
    ":modules:aether-client",
    ":modules:aether-client-testkit",
    ":modules:aether-cluster-api",
    ":modules:aether-cluster-codec",
    ":modules:aether-cluster-core",
    ":modules:aether-codec",
    ":modules:aether-embedded-typed",
    ":examples:aether-sample-app",
)
