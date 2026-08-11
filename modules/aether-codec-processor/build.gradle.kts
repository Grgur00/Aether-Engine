plugins {
    id("aether.java-library")
    id("aether.publishing")
}

dependencies {
    implementation(project(":modules:aether-codec-annotations"))
    testImplementation(project(":modules:aether-api"))
    testImplementation(project(":modules:aether-codec"))
}
