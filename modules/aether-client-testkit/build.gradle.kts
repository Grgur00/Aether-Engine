plugins { id("aether.java-library") }

dependencies {
    api(project(":modules:aether-client-api"))
    api(project(":modules:aether-client-codec"))
}
