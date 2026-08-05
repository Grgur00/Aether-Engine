plugins { id("aether.java-library") }

dependencies {
    api(project(":modules:aether-api"))
    implementation(project(":modules:aether-memory"))
}
