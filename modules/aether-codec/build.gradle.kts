plugins {
    id("aether.java-library")
    id("aether.publishing")
}

dependencies {
    api(project(":modules:aether-api"))
    api(project(":modules:aether-codec-annotations"))
}
