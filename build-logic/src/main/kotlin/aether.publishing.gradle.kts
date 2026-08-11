import org.gradle.api.publish.maven.MavenPublication

plugins { `maven-publish` }

fun MavenPublication.configurePom() {
    pom {
        name = project.name
        description =
            "Aether Engine ${project.name.removePrefix("aether-").replace('-', ' ')} module"
        url = "https://github.com/Grgur00/Aether-Engine"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "grgur00"
                name = "Grgur00"
                email = "Grgur00@users.noreply.github.com"
                url = "https://github.com/Grgur00"
            }
        }
        scm {
            connection = "scm:git:https://github.com/Grgur00/Aether-Engine.git"
            developerConnection = "scm:git:ssh://git@github.com/Grgur00/Aether-Engine.git"
            url = "https://github.com/Grgur00/Aether-Engine"
        }
        issueManagement {
            system = "GitHub Issues"
            url = "https://github.com/Grgur00/Aether-Engine/issues"
        }
    }
}

fun configureMavenPublication(componentName: String) {
    publishing {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components[componentName])
                configurePom()
            }
        }
    }
}

publishing {
    repositories {
        maven {
            name = "staging"
            url = rootProject.layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }
}

pluginManager.withPlugin("java") {
    if (!pluginManager.hasPlugin("java-gradle-plugin")) configureMavenPublication("java")
}
pluginManager.withPlugin("java-platform") { configureMavenPublication("javaPlatform") }
pluginManager.withPlugin("java-gradle-plugin") {
    publishing.publications.withType<MavenPublication>().configureEach { configurePom() }
}
