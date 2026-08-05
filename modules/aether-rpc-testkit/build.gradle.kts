plugins { id("aether.java-library") }

dependencies {
    api(project(":modules:aether-rpc-codec"))
    api(project(":modules:aether-rpc-transport"))
}
