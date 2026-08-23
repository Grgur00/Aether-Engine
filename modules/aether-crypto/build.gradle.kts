plugins { id("aether.java-library") }

dependencies {
    implementation(project(":modules:aether-reliability"))
    testImplementation(project(":modules:aether-io"))
}
