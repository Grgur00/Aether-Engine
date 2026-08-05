plugins { id("aether.java-application") }

dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-codec"))
    implementation(project(":modules:aether-engine"))
    implementation(project(":modules:aether-rpc-codec"))
    implementation(project(":modules:aether-replicated-log"))
}

application {
    mainClass = "io.aetherdb.workbench.AetherWorkbench"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
