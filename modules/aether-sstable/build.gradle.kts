plugins { id("aether.java-library") }

dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-memory"))
    implementation(project(":modules:aether-format"))
    implementation(project(":modules:aether-io"))
}
