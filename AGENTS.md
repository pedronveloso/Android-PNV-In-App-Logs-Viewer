# Working in this repository

This repository contains `:logviewer`, a reusable Compose library for viewing Timber logs, and
`:sample`, a small app for exercising the viewer. Keep the library independent of any host app's
theme, navigation, or log storage. Capture is opt-in: adding the dependency must not plant a
Timber tree or start collecting logs. The sample's release variant must continue to exclude the
viewer.

The `docs/` directory contains maintainer documentation. Follow `docs/release.md` when preparing
and publishing a Maven Central release.

`.github/workflows/opencode-review.yml` runs an advisory read-only review on pull requests. Treat
its findings as a second opinion, not a gate, and do not change the workflow to relax a check. The
job skips Dependabot pull requests, which receive no Actions secrets.

`.github/dependabot.yml` raises grouped dependency pull requests monthly for the Gradle version
catalog and the pinned GitHub Actions.

Treat log content as potentially sensitive. Apply redaction before captured entries reach memory
or disk, and preserve the behavior of caller-provided `LogSource` implementations. When changing
storage, filtering, follow behavior, or exports, add or update focused tests for the behavior.

`:logviewer` builds with Kotlin explicit API mode. Give every new public declaration an explicit
visibility modifier (`public`, `internal`, or `private`); the compiler enforces this on build.

Automated ABI/binary-compatibility checking is not wired in yet: neither the standalone
`binary-compatibility-validator` plugin nor the Kotlin Gradle plugin's native `abiValidation()`
currently detects a binaries source for this module — both are unwired under AGP 9's built-in
Kotlin support for Android libraries (tracked upstream as KT-83410). Don't re-attempt this without
first checking whether that issue has shipped a fix; review public API diffs by hand until then.

## Definition of done

Before calling any piece of work complete:

1. Bump the `:logviewer` version when a change set modifies the library, and update the README
   dependency example to match. Follow the SemVer policy in the README; use the highest
   applicable bump for mixed changes. Changes that touch only CI workflows, docs, or the sample's
   own code do not need a version bump. Dependabot pull requests are also exempt: merge them
   without a version bump or dependency-example edit. The one exception is that a bump to AGP,
   Kotlin, or the Compose BOM must still update the toolchain sentence at the top of the README,
   because that line documents the tested combination.
2. Run `./gradlew spotlessApply`, then `./gradlew spotlessCheck`. Review the formatting changes.
3. Run the library unit tests with `./gradlew :logviewer:testDebugUnitTest`.
4. Run Android lint, including Compose lint, for both variants of both modules:
   `./gradlew :logviewer:lintDebug :logviewer:lintRelease :sample:lintDebug :sample:lintRelease`.
5. Fix failures and rerun the affected checks. Avoid broad lint suppressions or baselines; use a
   narrow suppression only for a demonstrated false positive.

For changes that affect the sample or variant wiring, also build both sample variants with
`./gradlew :sample:assembleDebug :sample:assembleRelease`. For changes to release dependency
boundaries, verify that the release APK still contains no viewer code or resources.

Report which checks ran and any remaining limitations when handing work back for review.
