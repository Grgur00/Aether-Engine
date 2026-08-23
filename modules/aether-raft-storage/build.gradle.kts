plugins { id("aether.java-library") }

dependencies {
    api(project(":modules:aether-raft-api"))
    implementation(project(":modules:aether-format"))
    implementation(project(":modules:aether-reliability"))
}
