# In-app log viewer

A Compose log viewer for Android apps that use Timber. It can capture logs itself or display logs
from an app-provided `LogSource`. The library has no automatic initializer: adding the dependency
alone does not plant a tree or collect logs.

The repository has a `:logviewer` Android library and a `:sample` app. It uses AGP 9.4.1, Kotlin
2.4.20, Compose BOM 2026.09.00, Java 17 bytecode, and minSdk 26.

## Include in a host app

Use Maven Central (already included by default in most Android projects) and add the dependency
only to the debug variant:

```kotlin
dependencies {
    debugImplementation("io.github.pedronveloso:logviewer:0.6.0")
}
```

For local development against this repository instead of the published artifact, add a composite
build to the host's `settings.gradle.kts`:

```kotlin
includeBuild("../pnv-in-app-logs-viewer")
```

Keep all references to the library in `src/debug/`. If production code needs to reach a debug
menu, use a small interface or variant-specific entry point with a no-op `src/release/`
implementation. A release build then has no viewer classes, manifest provider, or viewer resources.
Apps that deliberately want production access may use `implementation` instead.

The published Maven group is independent of the library's Kotlin package,
`com.pedronveloso.logviewer`.

## Versioning

The library uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Every change set
bumps the `:logviewer` version and keeps the dependency example above in sync. While the API is
pre-1.0, bump MINOR for new features or breaking changes and PATCH for fixes, documentation, or
tooling. From 1.0 onward, bump MAJOR for breaking API changes, MINOR for compatible features, and
PATCH for fixes, documentation, or tooling.
Use the highest applicable bump when a change set contains more than one kind of change.

## Publish a release

Follow the step-by-step [Maven Central release guide](docs/release.md). Only the `:logviewer`
release AAR is published. The project is licensed under [Apache 2.0](LICENSE).

## Capture with the library

Create one capture in the host `Application` and explicitly install it. Keep it somewhere the
debug screen can access it, such as a dependency-injection singleton:

```kotlin
val capture = TimberLogCapture(
  context = this,
  persistAcrossCrashes = true,
  redact = StandardLogRedactor::redact,
)
capture.install()
```

Then render it inside the app's theme and navigation:

```kotlin
LogViewer(source = capture, onBack = onBack)
```

`persistAcrossCrashes` defaults to `false`. Memory keeps the newest 1,000 entries by default.
Persistent capture keeps at most two 1 MiB segments for each of the three newest process sessions
under the app's private, non-backed-up storage. Storage initializes in the background, so
construction and `install()` do not perform disk I/O. Early logs are buffered and flushed in order;
a crash before initialization finishes can lose them. The startup buffer is limited to 2 MiB of
encoded entries, with older entries dropped and a Status error shown if it fills. Once storage is
ready, each accepted entry is synced before Timber returns, so logging can add disk latency on its
calling thread. Messages and separate throwable traces are each limited to 12,000 characters and
marked when truncated. An incomplete tail record after a crash is discarded while earlier complete
records remain readable. Disk failure leaves the in-memory store operating and appears on Status.
Persistence covers process crashes; it does not claim durability against device power loss.

`StandardLogRedactor` is an opt-in policy for URLs, common credential fields, Bearer and Basic
values, email addresses, and UUIDs. Pass `StandardLogRedactor::redact` as shown above to use it;
capture without a `redact` callback keeps text unchanged. The policy runs on messages, throwable
stack traces, and tags before either memory or disk storage. Pattern matching cannot recognize
every sensitive value, so avoid logging secrets and supply an app-specific callback if needed.
The callback should be fast and must not log through Timber, which would recurse. If it throws,
the affected field is replaced with `<redaction failed>`. Copy and share use captured entries;
sharing is a user action through an app-private temporary file and a non-exported `FileProvider`.

## Use an existing store

Implement `LogSource` to adapt an app's in-memory or persistent Timber store. Supply immutable
session and entry snapshots, `capabilities` and `health` state flows, and a `changes` flow if new
entries should appear automatically. Return `null` for `changes` if the viewer should offer only
manual refresh. `LogEntry.id` must be unique and increasing within each session so list rows and
Follow remain stable. Set `canClear = false` for a read-only source. Set
`throwableStackTrace` when a source has separate exception details; in that case, keep the trace
out of `message`. Existing sources that include traces in `message` continue to display as supplied.

Status cannot inspect arbitrary Timber trees. For an external source it reports persistence and
redaction as claims made by the host app and displays any read or write errors the adapter exposes.
The viewer uses the host's `MaterialTheme`; it does not depend on Lazulite or AltSea styles.

## Try the sample

Run `./gradlew :logviewer:testDebugUnitTest :sample:assembleDebug :sample:assembleRelease`, then
install and launch the debug sample. Generate logs, open Logs, and use search, severity chips,
Follow, copy, share, and Status. Tap **Crash app to test recovery**, relaunch the sample, open
Logs, and select the previous session. The sample's release variant contains no viewer dependency
or viewer screen.
