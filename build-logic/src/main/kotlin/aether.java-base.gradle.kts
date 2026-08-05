plugins { java }

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("--enable-preview", "-parameters", "-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-preview", "-XX:+HeapDumpOnOutOfMemoryError")
}

tasks.withType<JavaExec>().configureEach { jvmArgs("--enable-preview") }

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
