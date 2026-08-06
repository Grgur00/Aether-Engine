plugins { `kotlin-dsl` }

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
    jvmToolchain(21)
}

// VS Code may run a different Gradle version concurrently. Versioned generated output prevents
// incompatible Kotlin DSL accessors from corrupting the command-line build.
layout.buildDirectory = layout.projectDirectory.dir(".build/${gradle.gradleVersion}")

repositories {
    gradlePluginPortal()
    mavenCentral()
}
