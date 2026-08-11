plugins {
    id("aether.java-library")
    id("aether.publishing")
}

dependencies {
    implementation(project(":modules:aether-memory"))
    implementation(project(":modules:aether-format"))
}
