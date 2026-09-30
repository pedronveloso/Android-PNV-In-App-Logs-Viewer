# Release to Maven Central

This guide publishes `:logviewer` as `io.github.pedronveloso:logviewer:<version>`. The Kotlin
package remains `com.pedronveloso.logviewer`. The sample app is not published. The release
workflow uploads a signed release AAR, sources, Javadoc artifact, POM, and Gradle metadata to
Central Portal and publishes automatically after Central validates the deployment.

The release workflow uses the repository secrets `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `SIGNING_IN_MEMORY_KEY`, and `SIGNING_IN_MEMORY_KEY_PASSWORD`.
Keep the Portal token and private signing key in GitHub Actions secrets, never in the repository.

## Prepare each release

1. Choose a version using the [README versioning policy](../README.md#versioning). Update
   `version` in `logviewer/build.gradle.kts` and the README dependency example to the same value.
2. Run the checks required by `AGENTS.md` from the repository root:

   ```bash
   ./gradlew spotlessApply
   ./gradlew spotlessCheck
   ./gradlew :logviewer:testDebugUnitTest
   ./gradlew :sample:test
   ./gradlew :logviewer:lintDebug :logviewer:lintRelease :sample:lintDebug :sample:lintRelease
   ./gradlew :sample:assembleDebug :sample:assembleRelease
   ```

   Review changes from formatting. Confirm the sample release APK excludes viewer code and
   resources. The release workflow repeats these checks and checks the APK for viewer markers.
3. Review the changes and publishable metadata. The Gradle publication uses the release AAR only;
   its POM must carry the correct group, artifact, version, Apache 2.0 license, developer, SCM, and
   transitive dependencies. Keep the [license file](../LICENSE) in the release commit.
4. Merge the reviewed changes into `main`, then update your local `main` and tag that commit. For
   the current library version, run `git switch main`, `git pull --ff-only origin main`, then
   `version=$(./gradlew -q :logviewer:properties --property version | sed -n 's/^version: //p')`,
   `git tag "v$version"`, and `git push origin "v$version"`. The tag push starts the release
   workflow automatically. It rejects tags whose commit is not on `main` or whose version differs
   from `:logviewer`. Do not
   move a tag for a version already published to Central, where releases are immutable.

## Monitor publication

1. On GitHub, open **Actions → Publish to Maven Central** for the pushed tag. Its formatting,
   library and sample unit tests, lint, sample builds, and release APK check must pass before the
   publish step starts. The publish step waits until Central reports the deployment as published;
   no manual GitHub start or Central Portal approval is needed.
2. Inspect the workflow result and, if needed, the validation details in
   [Central Portal deployments](https://central.sonatype.com/publishing/deployments). If a credential
   or transient failure needs no source change, correct it and rerun the same tag. If the release
   commit must change, choose a new version and tag. A version already published by Central cannot
   be replaced.
3. Once the deployment is published and available, confirm a fresh Android project resolves
   `io.github.pedronveloso:logviewer:<version>` through `mavenCentral()`. Host apps should use
   `debugImplementation` unless production access is intentional.
