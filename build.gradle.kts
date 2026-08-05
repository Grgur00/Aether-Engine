plugins { base }

group = "io.aetherdb"
version = providers.gradleProperty("aetherVersion").getOrElse("0.1.0-SNAPSHOT")

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs all module integration tests."
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

tasks.register("crashTestSmoke") {
    group = "verification"
    description = "Runs the fast deterministic crash-test fixture suite."
}

tasks.register("crashTest") {
    group = "verification"
    description = "Runs the complete crash-test suite."
}
