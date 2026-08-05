import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.SourceSetContainer

plugins { base }

group = "io.aetherdb"
version = providers.gradleProperty("aetherVersion").getOrElse("0.1.0-SNAPSHOT")

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs all module integration tests."
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

tasks.register("crashTestSmoke") {
    group = "verification"
    description = "Runs the fast deterministic crash-test fixture suite."
}

tasks.register("crashTest") {
    group = "verification"
    description = "Runs the complete crash-test suite."
}

val schemaInit = tasks.register("aetherSchemaInit") {
    group = "aether schema"
    description = "Proposes initial committed schema locks under each project's build directory."
}

val schemaUpdate = tasks.register("aetherSchemaUpdate") {
    group = "aether schema"
    description = "Proposes schema-lock updates without modifying committed locks."
}

val schemaAccept = tasks.register("aetherSchemaAccept") {
    group = "aether schema"
    description = "Explicitly accepts generated schema proposals into source-controlled directories."
}

val schemaCheck = tasks.register("aetherSchemaCheck") {
    group = "verification"
    description = "Verifies that source records match committed Aether schema locks."
}

subprojects {
    plugins.withId("java") {
        val sourceSets = extensions.getByType<SourceSetContainer>()
        val schemaDirectory = layout.projectDirectory.dir("aether-schemas")
        val proposalDirectory = layout.buildDirectory.dir("aether-schema/proposal")
        val processorPath = configurations.named("annotationProcessor")

        fun proposalTask(name: String, descriptionText: String) = tasks.register<JavaCompile>(name) {
            group = "aether schema"
            description = descriptionText
            source(sourceSets.named("main").get().allJava)
            classpath = sourceSets.named("main").get().compileClasspath
            javaCompiler = tasks.named<JavaCompile>("compileJava").flatMap { it.javaCompiler }
            options.annotationProcessorPath = processorPath.get()
            destinationDirectory = layout.buildDirectory.dir("aether-schema/classes")
            options.generatedSourceOutputDirectory = layout.buildDirectory.dir("aether-schema/generated")
            options.compilerArgs.addAll(listOf(
                "-proc:only",
                "-Aaether.schemaMode=PROPOSE",
                "-Aaether.schemaDirectory=${schemaDirectory.asFile.absolutePath}",
                "-Aaether.schemaProposalDirectory=${proposalDirectory.get().asFile.absolutePath}",
            ))
            inputs.dir(schemaDirectory).optional()
            outputs.dir(proposalDirectory)
        }

        val projectInit = proposalTask(
            "aetherSchemaInit", "Proposes initial Aether schema locks without changing source files.")
        val projectUpdate = proposalTask(
            "aetherSchemaUpdate", "Proposes updated Aether schema locks without changing source files.")
        val projectAccept = tasks.register("aetherSchemaAccept") {
            group = "aether schema"
            description = "Accepts this project's latest Aether schema proposal."
            dependsOn(projectUpdate)
            doLast {
                val proposal = proposalDirectory.get().asFile
                if (proposal.resolve("index.json").isFile) {
                    schemaDirectory.asFile.mkdirs()
                    copy { from(proposal); into(schemaDirectory) }
                }
            }
        }
        schemaInit.configure { dependsOn(projectInit) }
        schemaUpdate.configure { dependsOn(projectUpdate) }
        schemaAccept.configure { dependsOn(projectAccept) }
        schemaCheck.configure { dependsOn(tasks.named("compileJava")) }

        afterEvaluate {
            if (processorPath.get().dependencies.isNotEmpty()) {
                tasks.named<JavaCompile>("compileJava") {
                    inputs.dir(schemaDirectory).optional()
                    options.compilerArgs.add(
                        "-Aaether.schemaDirectory=${schemaDirectory.asFile.absolutePath}")
                }
            }
        }
    }
}
