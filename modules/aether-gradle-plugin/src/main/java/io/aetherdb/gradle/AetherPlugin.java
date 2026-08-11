package io.aetherdb.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

import java.io.File;
import java.util.List;

/** Configures Aether dependencies, generated codecs, and durable schema-lock tasks. */
public final class AetherPlugin implements Plugin<Project> {
    private static final String GROUP = "io.github.grgur00";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");

        String version = implementationVersion();
        project.getDependencies()
                .add("implementation", GROUP + ":aether-embedded-typed:" + version);
        project.getDependencies().add("implementation", GROUP + ":aether-codec:" + version);
        project.getDependencies()
                .add("annotationProcessor", GROUP + ":aether-codec-processor:" + version);

        configureJava(project);
        configureSchemaTasks(project);
    }

    private static void configureJava(Project project) {
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(JavaLanguageVersion.of(21));

        project.getTasks()
                .withType(JavaCompile.class)
                .configureEach(
                        task -> {
                            task.getOptions().getRelease().set(21);
                            task.getOptions().setEncoding("UTF-8");
                            task.getOptions().getCompilerArgs().add("--enable-preview");
                        });
        project.getTasks()
                .withType(Test.class)
                .configureEach(task -> task.jvmArgs("--enable-preview"));
        project.getTasks()
                .withType(JavaExec.class)
                .configureEach(task -> task.jvmArgs("--enable-preview"));
    }

    private static void configureSchemaTasks(Project project) {
        SourceSetContainer sourceSets =
                project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        Directory schemaDirectory =
                project.getLayout().getProjectDirectory().dir("aether-schemas");
        Provider<Directory> proposalDirectory =
                project.getLayout().getBuildDirectory().dir("aether-schema/proposal");
        Configuration processorPath =
                project.getConfigurations().getByName("annotationProcessor");
        TaskProvider<JavaCompile> compileJava =
                project.getTasks().named("compileJava", JavaCompile.class);

        TaskProvider<JavaCompile> initialize =
                proposalTask(
                        project,
                        "aetherSchemaInit",
                        "Proposes initial Aether schema locks without changing source files.",
                        main,
                        schemaDirectory,
                        proposalDirectory,
                        processorPath,
                        compileJava);
        TaskProvider<JavaCompile> update =
                proposalTask(
                        project,
                        "aetherSchemaUpdate",
                        "Proposes updated Aether schema locks without changing source files.",
                        main,
                        schemaDirectory,
                        proposalDirectory,
                        processorPath,
                        compileJava);

        project.getTasks()
                .register(
                        "aetherSchemaAccept",
                        task -> {
                            task.setGroup("aether schema");
                            task.setDescription(
                                    "Accepts the latest generated Aether schema proposal.");
                            task.dependsOn(update);
                            task.doLast(
                                    ignored -> {
                                        File proposal = proposalDirectory.get().getAsFile();
                                        if (new File(proposal, "index.json").isFile()) {
                                            project.copy(
                                                    copy -> {
                                                        copy.from(proposal);
                                                        copy.into(schemaDirectory);
                                                    });
                                        }
                                    });
                        });

        project.getTasks()
                .register(
                        "aetherSchemaCheck",
                        task -> {
                            task.setGroup("verification");
                            task.setDescription(
                                    "Verifies source records against committed schema locks.");
                            task.dependsOn(compileJava);
                        });

        compileJava.configure(
                task -> {
                    if (schemaDirectory.getAsFile().isDirectory()) {
                        task.getInputs().dir(schemaDirectory);
                    }
                    task.getOptions()
                            .getCompilerArgs()
                            .add(
                                    "-Aaether.schemaDirectory="
                                            + schemaDirectory.getAsFile().getAbsolutePath());
                });
    }

    private static TaskProvider<JavaCompile> proposalTask(
            Project project,
            String name,
            String description,
            SourceSet main,
            Directory schemaDirectory,
            Provider<Directory> proposalDirectory,
            Configuration processorPath,
            TaskProvider<JavaCompile> compileJava) {
        return project.getTasks()
                .register(
                        name,
                        JavaCompile.class,
                        task -> {
                            task.setGroup("aether schema");
                            task.setDescription(description);
                            task.source(main.getAllJava());
                            task.setClasspath(main.getCompileClasspath());
                            task.getJavaCompiler()
                                    .set(compileJava.flatMap(JavaCompile::getJavaCompiler));
                            task.getOptions().setAnnotationProcessorPath(processorPath);
                            task.getDestinationDirectory()
                                    .set(
                                            project.getLayout()
                                                    .getBuildDirectory()
                                                    .dir("aether-schema/classes"));
                            task.getOptions()
                                    .getGeneratedSourceOutputDirectory()
                                    .set(
                                            project.getLayout()
                                                    .getBuildDirectory()
                                                    .dir("aether-schema/generated"));
                            task.getOptions()
                                    .getCompilerArgs()
                                    .addAll(
                                            List.of(
                                                    "-proc:only",
                                                    "-Aaether.schemaMode=PROPOSE",
                                                    "-Aaether.schemaDirectory="
                                                            + schemaDirectory
                                                                    .getAsFile()
                                                                    .getAbsolutePath(),
                                                    "-Aaether.schemaProposalDirectory="
                                                            + proposalDirectory
                                                                    .get()
                                                                    .getAsFile()
                                                                    .getAbsolutePath()));
                            if (schemaDirectory.getAsFile().isDirectory()) {
                                task.getInputs().dir(schemaDirectory);
                            }
                            task.getOutputs().dir(proposalDirectory);
                        });
    }

    private static String implementationVersion() {
        String version = AetherPlugin.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("Aether plugin implementation version is missing");
        }
        return version;
    }
}
