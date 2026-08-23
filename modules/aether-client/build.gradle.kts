plugins { id("aether.java-library") }

dependencies {
    api(project(":modules:aether-api"))
    api(project(":modules:aether-client-api"))
    implementation(project(":modules:aether-codec"))
    implementation(project(":modules:aether-client-codec"))
    implementation(project(":modules:aether-rpc-api"))
}
