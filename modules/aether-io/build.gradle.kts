plugins { id("aether.java-library") }

dependencies {
    implementation(project(":modules:aether-memory"))
    implementation(project(":modules:aether-format"))
}
