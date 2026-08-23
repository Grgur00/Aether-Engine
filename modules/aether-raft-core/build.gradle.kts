plugins { id("aether.java-library") }

dependencies {
    api(project(":modules:aether-raft-api"))
    implementation(project(":modules:aether-config"))
    implementation(project(":modules:aether-reliability"))
}
