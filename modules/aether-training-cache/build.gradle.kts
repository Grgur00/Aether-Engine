import org.gradle.api.tasks.JavaExec

plugins { id("aether.java-library") }

dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-config"))
    implementation(project(":modules:aether-engine"))
}

tasks.register("paperRuntimeClasspath") {
    group = "verification"
    description = "Builds the Java engine and exports its runtime classpath for paper scripts."
    dependsOn("classes")
    doLast {
        val output = layout.buildDirectory.file("paper-runtime-classpath.txt").get().asFile
        output.parentFile.mkdirs()
        output.writeText(sourceSets.main.get().runtimeClasspath.asPath)
        println("Paper runtime classpath: $output")
    }
}

tasks.register<JavaExec>("trainingCacheBenchmark") {
    group = "benchmark"
    description = "Runs the training-cache cold and warm reuse benchmark."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.aetherdb.training.cache.TrainingCacheBenchmark")
    providers.gradleProperty("jfrFile").orNull?.let { file ->
        val requested = project.file(file).absolutePath
        val recording = if (requested.endsWith(".jfr")) requested else "$requested.jfr"
        jvmArgs("-XX:StartFlightRecording=filename=$recording,settings=profile,dumponexit=true")
    }
    args(
        providers.gradleProperty("benchmarkDir").orElse("build/training-cache-benchmark").get(),
        providers.gradleProperty("samples").orElse("1000").get(),
        providers.gradleProperty("payloadBytes").orElse("256").get(),
        providers.gradleProperty("output").orElse("build/training-cache-benchmark.json").get())
}

tasks.register<JavaExec>("trainingCacheCrashCampaign") {
    group = "verification"
    description = "Runs forced-process training-cache recovery trials."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.aetherdb.training.cache.TrainingCacheCrashCampaign")
    args(
        providers.gradleProperty("campaignDir").orElse("build/training-cache-crashes").get(),
        providers.gradleProperty("trials").orElse("1000").get())
}

tasks.register<JavaExec>("trainingCacheDaemon") {
    group = "application"
    description = "Starts the loopback training-cache daemon."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.aetherdb.training.cache.TrainingCacheDaemon")
    providers.gradleProperty("jfrFile").orNull?.let { file ->
        val requested = project.file(file).absolutePath
        val recording = if (requested.endsWith(".jfr")) requested else "$requested.jfr"
        jvmArgs("-XX:StartFlightRecording=filename=$recording,settings=profile,dumponexit=true")
    }
    args(
        providers.gradleProperty("cacheDir").orElse("build/training-cache-daemon").get(),
        providers.gradleProperty("port").orElse("9484").get())
}

tasks.register<JavaExec>("trainingCachePopulate") {
    group = "application"
    description = "Populates deterministic values for the Python pipeline benchmark."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.aetherdb.training.cache.TrainingCachePopulate")
    args(
        providers.gradleProperty("cacheDir").orElse("build/training-cache-daemon").get(),
        providers.gradleProperty("samples").orElse("128").get(),
        providers.gradleProperty("payloadBytes").orElse("1048576").get())
}

tasks.register("trainingCacheTestReport") {
    group = "verification"
    description = "Runs training-cache tests and writes a JSON result report."
    dependsOn("test")
    doLast {
        val reportDirectory = layout.buildDirectory.dir("reports").get().asFile
        val xmlDirectory = layout.buildDirectory.dir("test-results/test").get().asFile
        val suites = xmlDirectory.listFiles { file -> file.extension == "xml" }?.sortedBy { it.name }.orEmpty()
        var tests = 0
        var failures = 0
        var errors = 0
        var skipped = 0
        val cases = mutableListOf<Map<String, Any>>()
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        for (file in suites) {
            val suite = factory.newDocumentBuilder().parse(file).documentElement
            tests += suite.getAttribute("tests").toIntOrNull() ?: 0
            failures += suite.getAttribute("failures").toIntOrNull() ?: 0
            errors += suite.getAttribute("errors").toIntOrNull() ?: 0
            skipped += suite.getAttribute("skipped").toIntOrNull() ?: 0
            val testCases = suite.getElementsByTagName("testcase")
            for (index in 0 until testCases.length) {
                val testCase = testCases.item(index) as org.w3c.dom.Element
                val status = when {
                    testCase.getElementsByTagName("failure").length > 0 -> "failed"
                    testCase.getElementsByTagName("error").length > 0 -> "error"
                    testCase.getElementsByTagName("skipped").length > 0 -> "skipped"
                    else -> "passed"
                }
                cases += mapOf(
                    "suite" to testCase.getAttribute("classname"),
                    "name" to testCase.getAttribute("name"),
                    "status" to status,
                    "durationSeconds" to (testCase.getAttribute("time").toDoubleOrNull() ?: 0.0))
            }
        }
        reportDirectory.mkdirs()
        val report = mapOf(
            "module" to "aether-training-cache",
            "tests" to tests,
            "failures" to failures,
            "errors" to errors,
            "skipped" to skipped,
            "passed" to tests - failures - errors - skipped,
            "suites" to suites.map { it.name },
            "cases" to cases)
        val output = reportDirectory.resolve("training-cache-test.json")
        output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(report)))
        println("JSON test report: $output")
    }
}
