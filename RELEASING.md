# Releasing to Maven Central

Aether publishes production libraries under `io.github.grgur00`. Examples,
benchmarks, desktop applications, and test-support modules are intentionally
excluded from the public release.

## One-time setup

1. Sign in to the [Central Publisher Portal](https://central.sonatype.com/) with
   the `Grgur00` GitHub account and verify the automatically provisioned
   `io.github.grgur00` namespace.
2. Create a Central Portal user token.
3. Create an armored OpenPGP key pair whose public identity is appropriate for
   releases. Publish the public key to a commonly used key server.
4. Create a protected GitHub environment named `maven-central`.
5. Add these environment secrets:

   - `MAVEN_CENTRAL_USERNAME`
   - `MAVEN_CENTRAL_TOKEN`
   - `GPG_PUBLIC_KEY`
   - `GPG_SECRET_KEY`
   - `GPG_PASSPHRASE`

Never commit publishing credentials or private signing material.

## Validate locally

Choose an unreleased semantic version and build the complete Maven-layout
staging repository:

```bash
./gradlew check verifyReleaseVersion stageMavenCentral \
  -PaetherVersion=0.1.0
python3 scripts/check-maven-publication.py 0.1.0
```

Inspect `build/staging-deploy/io/github/grgur00`. Every library must contain a
main JAR, sources JAR, Javadoc JAR, Gradle module metadata, and POM. The BOM is
a POM-only platform.

The staging task does not sign or upload anything. JReleaser performs Central
validation, checksum generation, signing, and upload only in the protected
GitHub workflow.

## Publish

1. Ensure the release version has never been published. Maven Central releases
   are immutable.
2. Run the **Publish to Maven Central** workflow from GitHub Actions and enter
   the version.
3. Approve the protected `maven-central` environment if approval is enabled.
4. Confirm the deployment in Central Portal and verify the coordinates on
   Maven Central after synchronization.

Do not publish `-SNAPSHOT` versions through this workflow. JReleaser's Central
Portal deployer accepts releases only.
