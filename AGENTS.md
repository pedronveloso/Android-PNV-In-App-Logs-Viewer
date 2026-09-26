# Working in this repository

This repository contains `:logviewer`, a reusable Compose library for viewing Timber logs, and
`:sample`, a small app for exercising the viewer. Keep the library independent of any host app's
theme, navigation, or log storage. Capture is opt-in: adding the dependency must not plant a
Timber tree or start collecting logs. The sample's release variant must continue to exclude the
viewer.

Treat log content as potentially sensitive. Apply redaction before captured entries reach memory
or disk, and preserve the behavior of caller-provided `LogSource` implementations. When changing
storage, filtering, follow behavior, or exports, add or update focused tests for the behavior.

## Definition of done

Before calling any piece of work complete:

1. Run `./gradlew spotlessApply`, then `./gradlew spotlessCheck`. Review the formatting changes.
2. Run the library unit tests with `./gradlew :logviewer:testDebugUnitTest`.
3. Run Android lint, including Compose lint, for both variants of both modules:
   `./gradlew :logviewer:lintDebug :logviewer:lintRelease :sample:lintDebug :sample:lintRelease`.
4. Fix failures and rerun the affected checks. Avoid broad lint suppressions or baselines; use a
   narrow suppression only for a demonstrated false positive.

For changes that affect the sample or variant wiring, also build both sample variants with
`./gradlew :sample:assembleDebug :sample:assembleRelease`. For changes to release dependency
boundaries, verify that the release APK still contains no viewer code or resources.

Report which checks ran and any remaining limitations when handing work back for review.
