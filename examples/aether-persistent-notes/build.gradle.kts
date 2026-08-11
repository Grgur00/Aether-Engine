plugins { id("aether.java-application") }

dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-codec"))
    implementation(project(":modules:aether-embedded-typed"))
    annotationProcessor(project(":modules:aether-codec-processor"))
}

application { mainClass = "io.aetherdb.examples.notes.PersistentNotesApplication" }

tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
