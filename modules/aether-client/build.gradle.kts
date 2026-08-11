plugins { id("aether.java-library") }

dependencies {
    api(project(":modules:aether-client-api"))
    implementation(project(":modules:aether-client-codec"))
    implementation(project(":modules:aether-rpc-api"))
}
