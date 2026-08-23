plugins {
    id("aether.java-library")
    id("aether.publishing")
}

dependencies {
    api(project(":modules:aether-api"))
    implementation(project(":modules:aether-admission"))
    implementation(project(":modules:aether-config"))
    implementation(project(":modules:aether-memory"))
    implementation(project(":modules:aether-format"))
    implementation(project(":modules:aether-observability-api"))
    implementation(project(":modules:aether-reliability"))
    implementation(project(":modules:aether-io"))
    implementation(project(":modules:aether-memtable"))
    implementation(project(":modules:aether-wal"))
    implementation(project(":modules:aether-sstable"))
    implementation(project(":modules:aether-lsm"))
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-preview") }
