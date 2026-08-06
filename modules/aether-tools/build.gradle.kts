plugins { id("aether.java-application") }

dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-engine"))
    implementation(project(":modules:aether-format"))
    implementation(project(":modules:aether-io"))
    implementation(project(":modules:aether-sstable"))
    implementation(project(":modules:aether-wal"))
}

application { mainClass = "io.aetherdb.tools.AetherCli" }
