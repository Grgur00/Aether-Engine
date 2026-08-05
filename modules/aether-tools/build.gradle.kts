plugins { id("aether.java-application") }

dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-engine"))
    implementation(project(":modules:aether-io"))
}

application { mainClass = "io.aetherdb.tools.AetherCli" }
