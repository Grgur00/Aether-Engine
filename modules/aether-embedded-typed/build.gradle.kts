plugins {
    id("aether.java-library")
    id("aether.publishing")
}

dependencies {
    api(project(":modules:aether-api"))
    api(project(":modules:aether-codec-annotations"))
    implementation(project(":modules:aether-codec"))
    implementation(project(":modules:aether-engine"))
    // Keep test-domain annotations visible to IDE Java language servers as well as javac.
    testImplementation(project(":modules:aether-codec-annotations"))
    testAnnotationProcessor(project(":modules:aether-codec-processor"))
}

tasks.named<JavaCompile>("compileTestJava") {
    options.compilerArgs.add(
        "-Aaether.schemaDirectory=${project.projectDir}/src/test/aether-schemas"
    )
}
