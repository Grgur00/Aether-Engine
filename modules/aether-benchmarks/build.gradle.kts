plugins { id("aether.java-application") }

dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-engine"))
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
}

application { mainClass = "io.aetherdb.benchmarks.CvBenchmark" }

tasks.named<JavaExec>("run") {
    if (System.getenv("AETHER_JFR") == "true") {
        val recording = layout.buildDirectory
            .file("jfr/aether-benchmark.jfr")
            .get()
            .asFile

        recording.parentFile.mkdirs()

        jvmArgs(
            "-XX:StartFlightRecording=" +
                "filename=${recording.absolutePath}," +
                "settings=profile," +
                "disk=true," +
                "dumponexit=true," +
                "maxsize=2g"
        )
    }
}
