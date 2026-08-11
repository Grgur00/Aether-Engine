plugins { id("aether.java-library") }

dependencies {
    api(project(":modules:aether-raft-api"))
    api(project(":modules:aether-raft-core"))
}
