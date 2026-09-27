# Release to Maven Central

This guide publishes `:logviewer` as `io.github.pedronveloso:logviewer:<version>`. The Kotlin
package remains `com.pedronveloso.logviewer`. The sample app is not published. The release
workflow uploads a signed release AAR, sources, Javadoc artifact, POM, and Gradle metadata to
Central Portal; publication requires a separate manual approval there.

## Prepare each release

1. Choose a version using the [README versioning policy](../README.md#versioning). Update
   `version` in `logviewer/build.gradle.kts` and the README dependency example to the same value.
2. Run the checks required by `AGENTS.md` from the repository root:

   ```bash
   ./gradlew spotlessApply
   ./gradlew spotlessCheck
   ./gradlew :logviewer:testDebugUnitTest
   ./gradlew :logviewer:lintDebug :logviewer:lintRelease :sample:lintDebug :sample:lintRelease
   ./gradlew :sample:assembleDebug :sample:assembleRelease
   ```

   Review changes from formatting. Confirm the sample release APK excludes viewer code and
   resources. The release workflow repeats these checks and checks the APK for viewer markers.
3. Review the changes and publishable metadata. The Gradle publication uses the release AAR only;
   its POM must carry the correct group, artifact, version, Apache 2.0 license, developer, SCM, and
   transitive dependencies. Keep the [license file](../LICENSE) in the release commit.
4. Commit and push the reviewed changes. Tag that exact commit with `v<version>` and push the tag.
   For `1.0.0`, run `git tag v1.0.0` and `git push origin v1.0.0` after pushing the commit. Do not
   move a tag for a version already published to Central, where releases are immutable.

## Upload and publish

1. On GitHub, open **Actions → Publish to Maven Central → Run workflow**. Enter the version without
   `v` (for example, `1.0.0`) and start the workflow. It checks out `v<version>`, rejects a tag whose
   library version differs, runs release checks, signs the artifacts, and uploads a deployment.
2. Inspect the workflow result. If a credential or transient failure needs no source change,
   correct it and rerun the same tag. If the release commit must change, choose a new version and
   tag. A version already published by Central cannot be replaced.
3. Open [Central Portal deployments](https://central.sonatype.com/publishing/deployments). Review
   the deployment and validation results, then click **Publish**. The workflow does not make the
   release public automatically.
4. Once the deployment is published and available, confirm a fresh Android project resolves
   `io.github.pedronveloso:logviewer:<version>` through `mavenCentral()`. Host apps should use
   `debugImplementation` unless production access is intentional.
