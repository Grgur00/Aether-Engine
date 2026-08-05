plugins { id("aether.java-library") }

dependencies {
    api(project(":modules:aether-replication-api"))
    implementation(project(":modules:aether-replicated-log"))
}
