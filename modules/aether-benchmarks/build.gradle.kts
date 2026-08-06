plugins { id("aether.java-application") }

dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-engine"))
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
}

application { mainClass = "io.aetherdb.benchmarks.CvBenchmark" }
