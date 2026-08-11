plugins {
    id("aether.java-library")
    id("aether.publishing")
}

dependencies { api(project(":modules:aether-api")) }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-preview") }
