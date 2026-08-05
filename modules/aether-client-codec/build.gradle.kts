plugins { id("aether.java-library") }
dependencies { api(project(":modules:aether-client-api")); implementation(project(":modules:aether-format")) }
