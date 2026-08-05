plugins { `kotlin-dsl` }

// VS Code may run a different Gradle version concurrently. Versioned generated output prevents
// incompatible Kotlin DSL accessors from corrupting the command-line build.
layout.buildDirectory = layout.projectDirectory.dir(".build/${gradle.gradleVersion}")

repositories {
    gradlePluginPortal()
    mavenCentral()
}
