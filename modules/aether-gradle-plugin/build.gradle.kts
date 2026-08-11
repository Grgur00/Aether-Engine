plugins {
    `java-gradle-plugin`
    id("aether.java-base")
    id("aether.testing")
    id("aether.publishing")
}

java {
    withSourcesJar()
    withJavadocJar()
}

gradlePlugin {
    plugins {
        create("aetherSchema") {
            id = "io.github.grgur00.aether"
            displayName = "Aether Engine"
            description =
                "Configures Aether dependencies, generated codecs, and durable schema locks"
            implementationClass = "io.aetherdb.gradle.AetherPlugin"
        }
    }
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
}
