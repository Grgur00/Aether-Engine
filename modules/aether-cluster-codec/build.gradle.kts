plugins { id("aether.java-library") }
dependencies {
    api(project(":modules:aether-cluster-api"))
    implementation(project(":modules:aether-format"))
}
