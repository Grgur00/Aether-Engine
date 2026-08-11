plugins {
    id("aether.java-library")
    id("aether.publishing")
}

dependencies {
    api(project(":modules:aether-api"))
    implementation(project(":modules:aether-memory"))
}
