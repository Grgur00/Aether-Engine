plugins { id("aether.java-library") }

dependencies {
    api(project(":modules:aether-rpc-api"))
    implementation(project(":modules:aether-format"))
}
