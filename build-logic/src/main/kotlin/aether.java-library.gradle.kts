plugins {
    `java-library`
    id("aether.java-base")
    id("aether.testing")
}

java {
    withSourcesJar()
    withJavadocJar()
}
