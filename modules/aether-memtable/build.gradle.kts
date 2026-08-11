plugins {
    id("aether.java-library")
    id("aether.publishing")
}

dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-memory"))
    implementation(project(":modules:aether-format"))
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-preview") }
