plugins {
    `java-platform`
    id("aether.publishing")
}

javaPlatform { allowDependencies() }

dependencies {
    constraints {
        api(project(":modules:aether-api"))
        api(project(":modules:aether-memory"))
        api(project(":modules:aether-format"))
        api(project(":modules:aether-io"))
        api(project(":modules:aether-memtable"))
        api(project(":modules:aether-wal"))
        api(project(":modules:aether-sstable"))
        api(project(":modules:aether-lsm"))
        api(project(":modules:aether-engine"))
        api(project(":modules:aether-codec-annotations"))
        api(project(":modules:aether-codec"))
        api(project(":modules:aether-codec-processor"))
        api(project(":modules:aether-embedded-typed"))
        api(project(":modules:aether-gradle-plugin"))
    }
}
