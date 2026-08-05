plugins { id("aether.java-library") }

dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-memory"))
    implementation(project(":modules:aether-format"))
    implementation(project(":modules:aether-io"))
    implementation(project(":modules:aether-memtable"))
    implementation(project(":modules:aether-wal"))
    implementation(project(":modules:aether-sstable"))
}
