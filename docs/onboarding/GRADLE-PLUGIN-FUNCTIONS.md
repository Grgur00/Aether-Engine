# Gradle Plugin and Build Convention Functions

[Function index](FUNCTION-INDEX.md) | [Schemas](TYPED-API-AND-SCHEMAS.md) | [Testing](TESTING-AND-CONTRIBUTING.md)

This reference covers all **five explicit Java methods** in
[AetherPlugin.java](../../modules/aether-gradle-plugin/src/main/java/io/aetherdb/gradle/AetherPlugin.java)
and the two explicit Kotlin publishing helpers, plus the configuration actions in
all seven [shared convention scripts](../../build-logic/src/main/kotlin).
Gradle-generated accessors, implicit constructor and lambda bodies are explained
through their owning methods/actions, not counted as named Java declarations.

## Two Build Layers

```text
Repository includeBuild(build-logic)
    -> aether.java-base / testing / library / application conventions
    -> repository compilation and test tasks

Published io.github.grgur00.aether consumer plugin
    -> Java plugin + three versioned Aether dependencies
    -> Java 21 preview configuration
    -> compileJava schema verification + schema proposal/accept tasks
```

These are different plugins. Applying aether.java-application does not apply the
published consumer plugin. The [current sample build](../../examples/aether-sample-app/build.gradle.kts)
uses repository conventions/project dependencies. Root-build actions separately
register its schema init/update/accept tasks. Root aetherSchemaCheck aggregates
compilation; the root implementation does not register a sample-scoped check task.

## Consumer Plugin Methods

| Function | Configuration behavior | Boundaries and failures |
| --- | --- | --- |
| `AetherPlugin.apply(project)` | Applies java; reads plugin implementation version; adds implementation dependencies aether-embedded-typed and aether-codec, plus annotationProcessor aether-codec-processor, all under io.github.grgur00 at the same version. Invokes Java/schema configuration. | Does not configure dependency repositories, a BOM, application main class or publication. Missing implementation version fails after Java plugin application. Dependencies are coordinates, not project dependencies. |
| `AetherPlugin.configureJava(project)` | Sets Java toolchain and compiler release to 21, UTF-8 compilation and --enable-preview on all JavaCompile tasks. Adds preview JVM flag to all Test and JavaExec tasks via configureEach. | Does not enable JUnit Platform or add test dependencies, -parameters, warnings-as-errors, Javadoc flags or archive reproducibility. Later consumer configuration may override settings; JVM flags are appended. |
| `AetherPlugin.configureSchemaTasks(project)` | Finds main source set, annotationProcessor configuration and compileJava. Defines project aether-schemas and build aether-schema/proposal directories; registers two proposal compiles, accept and check. Adds schemaDirectory processor argument to compileJava; registers schema directory as input only if it exists when configuration action runs. | Depends on Java plugin extensions/tasks existing. No extension for custom schema paths or version. Proposal/accept are explicit source-changing workflow, not ordinary compilation. Processor default mode is VERIFY. |
| `AetherPlugin.proposalTask(project, name, description, main, schemaDirectory, proposalDirectory, processorPath, compileJava)` | Lazily registers JavaCompile using main Java sources/compile classpath, compileJava's compiler provider and annotation processor path. Outputs build/aether-schema/classes and generated sources under build/aether-schema/generated. Adds -proc:only, schemaMode=PROPOSE and absolute schema/proposal paths. Declares proposal directory output and existing schema input. | Init/update share all output locations and same proposal behavior; name/description differ. Provider wiring supplies compiler, not a dependency on completing normal compilation. Does not clean old proposal files itself or atomically accept them. No explicit coordination for both proposal tasks sharing output. |
| `AetherPlugin.implementationVersion()` | Reads Package.getImplementationVersion; returns nonblank value or throws IllegalStateException. | No fallback to project.version or system property. Loading unpackaged classes without manifest metadata can fail. |

The [plugin module build](../../modules/aether-gradle-plugin/build.gradle.kts)
registers ID io.github.grgur00.aether with implementation class AetherPlugin and
adds Implementation-Version=project.version to the JAR manifest. Sources/Javadoc
JARs and Maven publishing are build configuration, not work done by apply.

## Schema Task Actions

| Task/action | Execution | What remains caller-owned |
| --- | --- | --- |
| aetherSchemaInit | JavaCompile proposalTask with initial-lock description. | No separate empty-schema precondition in plugin; processor determines proposal content. |
| aetherSchemaUpdate | Same proposalTask settings and outputs as init, with update description. | No plugin-level compatibility decision; see schema processor references. |
| aetherSchemaAccept registration/doLast | Depends on update. If proposal/index.json is a file, Project.copy copies entire proposal tree into project/aether-schemas. Otherwise silently does nothing. | Not an atomic directory swap, validation of proposal inventory or removal of stale destination files. No plugin-level explicit source output/input declaration on accept. Review copied files before committing. |
| aetherSchemaCheck registration | Verification-group task depending on compileJava; no independent action. | Processor diagnostics during compilation provide the check. Task name alone adds no separate schema scanner. |
| compileJava.configure | Appends absolute aether.schemaDirectory processor option; existing schema directory is tracked as input. | Does not force processor execution when Gradle considers compilation up-to-date. No task changes to source-controlled schemas. |

Generated source compilation and lock verification belong to
[annotation processing](SCHEMA-ANNOTATION-FUNCTIONS.md) and
[schema resource functions](SCHEMA-RESOURCE-FUNCTIONS.md), not the plugin's copy action.
These methods do not create a durable database or verify a running deployment.

## Shared Java and Test Conventions

| Script/action | Behavior | Limits |
| --- | --- | --- |
| [aether.java-base.gradle.kts](../../build-logic/src/main/kotlin/aether.java-base.gradle.kts): plugins/java configuration | Applies java and selects Java 21 toolchain. | Toolchain selection is not an assertion that an arbitrary external launcher uses the same JDK. |
| java-base: JavaCompile.configureEach | release=21, UTF-8, --enable-preview, -parameters, -Xlint:all and -Werror. | Warnings become compile errors. These extra strict flags differ from consumer plugin configuration. |
| java-base: Test.configureEach | Adds --enable-preview and heap-dump-on-OOM JVM flag. | Does not itself choose JUnit engine, configure heap size or classify tests. |
| java-base: JavaExec.configureEach | Adds --enable-preview. | Does not create a run task without an application/other task registration. |
| java-base: Javadoc.configureEach | Enables preview and source 21 via StandardJavadocDocletOptions. | Cast expects standard doclet options. Not an exhaustive documentation-coverage check. |
| java-base: AbstractArchiveTask.configureEach | Disables preserved file timestamps and enables reproducible file order. | Does not remove timestamps embedded in contents or establish byte-for-byte reproducibility across differing inputs/toolchains. |
| [aether.testing.gradle.kts](../../build-logic/src/main/kotlin/aether.testing.gradle.kts) | Applies java; adds JUnit BOM 6.0.1, Jupiter, AssertJ 3.27.7, platform launcher; all Test tasks use JUnit Platform. | Does not register custom integration/crash source sets, test filters or failure-policy overrides. |
| [aether.java-library.gradle.kts](../../build-logic/src/main/kotlin/aether.java-library.gradle.kts) | Applies java-library, Java base/testing; enables sources and Javadoc JARs. | Does not apply publishing by itself. |
| [aether.java-application.gradle.kts](../../build-logic/src/main/kotlin/aether.java-application.gradle.kts) | Applies application, Java base/testing. | Main class is configured in consuming module; no consumer schema plugin. |
| [aether.jmh.gradle.kts](../../build-logic/src/main/kotlin/aether.jmh.gradle.kts) | Applies Java base only. | Comment describes intended benchmark isolation, but file adds no JMH dependency/plugin/source set or benchmark task. |
| [aether.quality.gradle.kts](../../build-logic/src/main/kotlin/aether.quality.gradle.kts) | Applies base only; an attachment point for future quality tools. | Does not run formatters, static analysis or pinned quality tools itself. |

## Publishing Helpers and Actions

Source: [aether.publishing.gradle.kts](../../build-logic/src/main/kotlin/aether.publishing.gradle.kts).

| Function/action | Behavior | Boundaries |
| --- | --- | --- |
| `MavenPublication.configurePom()` | Sets name to project.name and description from module name; adds repository URL, Apache 2.0 license, developer, SCM and issue tracker metadata. | Declared metadata, not license scanning, provenance verification or signing. |
| `configureMavenPublication(componentName)` | Creates mavenJava publication from named component and configures POM. | Requires component to exist; not an idempotent lookup. No artifact upload performed by helper itself. |
| publishing.repositories | Defines staging Maven repository at root build/staging-deploy URI. | Local staging is not Maven Central or a remote release. |
| withPlugin(java) | Unless java-gradle-plugin already present, creates publication from java component. | Conditional plugin state at callback time matters; no rollback of existing publications. |
| withPlugin(java-platform) | Creates mavenJava from javaPlatform component. | No automatic BOM contents here. |
| withPlugin(java-gradle-plugin) | Configures POM for each MavenPublication, including later-created ones. | Gradle plugin machinery owns publication creation; convention supplies metadata. |

The [root build](../../build.gradle.kts) separately chooses published modules,
version policy, staging aggregation and JReleaser configuration. This reference
does not inventory every root/module build action or certify a release upload.
Do not execute publishing or schema acceptance to test documentation.

## Root Schema Configuration

The root build defines root-level init/update/accept/check aggregate tasks. For
each subproject receiving java, its local Kotlin `proposalTask(name, descriptionText)`
registers init/update JavaCompile tasks with the same processor mode and output
locations as the consumer plugin. It declares the schema directory as an optional
input unconditionally, unlike the plugin's existence-based declaration.
Subproject accept depends on update, checks proposal/index.json, creates the schema
directory and copies the tree. Root aggregates depend on corresponding project
tasks; root check depends on each Java subproject's compileJava. No project check
task is registered here. After evaluation, projects with explicitly declared
annotationProcessor dependencies get schemaDirectory input/processor configuration
on normal compilation. This checks dependency declarations, not resolved processor
capabilities. Copy acceptance still has no atomic replacement or stale-file cleanup.

## Build-Logic Bootstrap and Verification

[build-logic/build.gradle.kts](../../build-logic/build.gradle.kts) applies kotlin-dsl,
sets Java/Kotlin toolchains to 21 and redirects output to .build/Gradle-version.
That separates generated Kotlin DSL outputs for different Gradle versions, not
every possible concurrent invocation of the same version. It uses plugin portal
and Maven Central repositories. The [included build settings](../../build-logic/settings.gradle.kts)
configure repositories, Foojay resolver 1.0.0 and aether-build-logic name.
The [root settings](../../settings.gradle.kts) include this build and enforce
FAIL_ON_PROJECT_REPOS for main dependency resolution.

No dedicated src/test tree is present in aether-gradle-plugin. Compiler-tree
documentation tests verify all five method names, not plugin application or TestKit
behavior. Building plugin classes/resources/JAR verifies compilation and packaging,
not proposal correctness, shared-output concurrency, task up-to-date semantics,
acceptance atomicity or published consumer dependency resolution. Those require
disposable consumer fixtures, including a packaged plugin with version metadata.
