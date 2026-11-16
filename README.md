# In-app log viewer

A Compose log viewer for Android apps that use Timber. It can capture logs itself or display logs
from an app-provided `LogSource`. The library has no automatic initializer: adding the dependency
alone does not plant a tree or collect logs.

The repository has a `:logviewer` Android library and a `:sample` app. It uses AGP 9.5.1, Kotlin
2.4.21, Compose BOM 2026.09.00, Java 17 bytecode, and minSdk 26.

## Include in a host app

Use Maven Central (already included by default in most Android projects) and add the dependency
only to the debug variant:

```kotlin
dependencies {
    debugImplementation("io.github.pedronveloso:logviewer:0.13.0")
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

`:logviewer` builds with Kotlin's [explicit API mode](https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors)
(`kotlin { explicitApi() }`), so every public declaration must carry an explicit visibility
modifier. This keeps the public surface intentional ahead of the SemVer policy above.

## Publish a release

Follow the step-by-step [Maven Central release guide](docs/release.md). Only the `:logviewer`
release AAR is published. The project is licensed under [Apache 2.0](LICENSE).

## Capture with the library

Create one capture in the host `Application` and explicitly install it. Keep it somewhere the
debug screen can access it, such as a dependency-injection singleton:

```kotlin
val capture =
  TimberLogCapture.Builder(this)
    .persistAcrossCrashes(true)
    .redact(StandardLogRedactor::redact)
    .build()
capture.install()
```

Every builder option has a default, so `TimberLogCapture.Builder(this).build()` is a valid
memory-only capture. Construct captures through the builder.

Then render it inside the app's theme and navigation:

```kotlin
LogViewer(source = capture, onBack = onBack)
```

`persistAcrossCrashes` defaults to `false`. Memory keeps the newest 1,000 entries by default.
Persistent capture keeps at most two 1 MiB segments for each of the three newest process sessions
under the app's private, non-backed-up storage. Storage initializes in the background, so
construction starts one background journal initialization, even before `install()`, while neither
call performs disk I/O on its calling thread. Early logs are buffered and flushed in order;
a crash before initialization finishes can lose them. The startup buffer is limited to 2 MiB of
encoded entries, with older entries dropped and a distinct Status notice shown if it fills. Once
storage is ready, each accepted entry is synced before Timber returns, so logging can add disk
latency on its calling thread. Messages and separate throwable traces are each limited to 12,000 characters and
marked when truncated. An incomplete tail record after a crash is discarded while earlier complete
records remain readable. Disk failure leaves the in-memory store operating and appears on Status.
Persistence covers process crashes; it does not claim durability against device power loss.

`StandardLogRedactor` is an opt-in policy for URLs, common credential fields, Bearer and Basic
values, email addresses, and UUIDs. Pass `StandardLogRedactor::redact` as shown above to use it;
capture without a `redact` callback keeps text unchanged. The policy runs on messages, throwable
stack traces, and tags before either memory or disk storage. Pattern matching cannot recognize
every sensitive value, so avoid logging secrets and supply an app-specific callback if needed.
The callback should be fast and must not log through Timber, which would recurse. If it throws,
the affected field is replaced with `<redaction failed>`. Share uses captured entries;
sharing is a user action through an app-private temporary file and a non-exported `FileProvider`.

Without a callback, the default choice is `RedactionOption.NOT_SPECIFIED`, and Status warns that
redaction is not configured. To intentionally keep log text unchanged without that warning, use
`.redact(RedactionOption.NO_REDACTION)` on the capture builder. Calling
`.redact(RedactionOption.NOT_SPECIFIED)` or `.redact(null)` restores the warning. The last `redact`
call wins, replacing any previous callback or opt-out choice.

The viewer also remembers one UI preference, whether long lines wrap in the entry details, in a
private `SharedPreferences` file named `pnv_logviewer`; it never stores log content there.

## Process exits

From Android 11 (API 30), the Status tab also lists the exits Android recorded for the app
(`ApplicationExitInfo`): when and why each process ended, what that reason means, where the app was
(on screen, a foreground service, the background), Android's own description and the memory it
sampled. A process can't see its own death, so these are earlier sessions; read them next to the
persisted logs of the session that died. On older versions the section says it's unavailable.

Some exits are flagged: by default memory-limit kills, crashes, native crashes and ANRs. While one
is pending, the Status tab title shows a warning icon, and the exit gets a card with a Dismiss
button. Dismissing stores the newest reviewed exit's timestamp in the same `pnv_logviewer`
preferences, so it stays reviewed after a restart.

Tracking is on by default. It only queries the system, so it plants no tree and collects nothing.
Descriptions go through the capture's `redact` callback. Configure or turn it off on the builder:

```kotlin
TimberLogCapture.Builder(this)
  .processExits { alertOn(setOf(ExitReason.MemoryLimit)).maxExits(32) }
  .build()

TimberLogCapture.Builder(this).processExits(enabled = false).build()
```

For host badges, collect the non-null `capture.warnings` flow with Compose's lifecycle-aware
collector (add `androidx.lifecycle:lifecycle-runtime-compose` to the host):

```kotlin
val warnings by capture.warnings.collectAsStateWithLifecycle()
BadgedBox(badge = { if (warnings.hasWarnings) Badge { Text("${warnings.totalCount}") } }) {
  Icon(Icons.Outlined.BugReport, contentDescription = "Logs")
}
```

This is a `StateFlow<LogWarnings>` for Compose or ordinary coroutine collectors. `warnings.warnings`
is a list of `LogWarning(type, count)` values with nonzero counts; currently the enum type is
`WarningType.PendingProcessExit`. Future versions may add categories, so handle unknown enum values
with a fallback when presenting them. WARN/ERROR log lines and optional configuration advice do not
contribute to this summary. Disabled tracking, unsupported platforms, loading and failed history
reads contribute no warnings; use `capture.processExits?.state` if you need to distinguish those
states. `processExits.acknowledge()` clears pending warnings and persists that choice. Refreshing
history updates the summary automatically. Apps that bring their own `LogSource` can collect
`ProcessExitHistory.warnings` in the same way.

`capture.processExits` remains available for integrations that need the full history, raw platform
fields or trace streams. Its `state` is a `StateFlow<ProcessExitState>`.

Tap an exit for its details sheet:
- Android's description and the exit status (an exit code, or the signal, such as `Signal 9 (SIGKILL)`).
- The memory sampled at exit, and the process name and PID.
- For ANRs on Android 17 and later, the ANR type, the timeout that was hit, and whether the user could see the hang.
- The log session the process was running, with a button to open its logs while the viewer still keeps them, and the app's note.

Data that is better read on a computer is shared rather than shown:
- **Share trace** appears when Android kept a trace. It sends an ANR's thread dump as `anr-<time>-<unique>.txt`, or a native crash tombstone (Android 12+) as `tombstone-<time>-<unique>.pb`. The tombstone is a protobuf following AOSP's [`tombstone.proto`](https://android.googlesource.com/platform/system/core/+/refs/heads/main/debuggerd/proto/tombstone.proto). Read it with AOSP's `pbtombstone`, or with `protoc --decode=Tombstone tombstone.proto < tombstone.pb`. Android keeps traces in a shared buffer, so an older one may already be gone.
- **Share details** sends every field as `exit-<time>-<unique>.txt`, with an ANR dump appended. If the linked session is still kept, you can include all its retained logs in the same file. Search and level filters do not affect these logs.

ANR dumps and native tombstones are raw platform diagnostics. The redactor does not apply to
`openTrace`, Share trace, or the ANR dump appended by Share details. These can contain sensitive
data even when log redaction is enabled.

Both use the same share chooser and app-private export folder as Share for logs.
`processExits.openTrace(exit)` gives host apps the same trace stream.

To link exits to log sessions, `install()` tags the process through
`ActivityManager.setProcessStateSummary` with the capture's session ID. Add a short note with
`capture.setExitNote("model: Voz")`; it is redacted and cut to fit Android's 128-byte limit. Android
throttles these writes, so set the note when the state changes, and keep personal data out of it.
Android keeps one summary per process, so an app that writes its own turns the link off with
`.linkExitsToSessions(false)`; its bytes show as text or hex through the history redactor.
Disabling process-exit tracking also disables session linking, regardless of builder-call order.
App notes are redacted only when written and retain that policy when read in later captures.

Call `refresh()` to read the history again. An app that brings its own `LogSource` builds a
history with `ProcessExitHistory.Builder(context)` and passes it to
`LogViewer(source, onBack, processExits = history)`.

Android 17's memory limiter is documented as `REASON_OTHER` with a description starting
`MemoryLimiter:`, but Android 17 builds record reason 17 ("SYSTEM MEMORY LIMIT EXCEEDED", described
as `[KILL PID] kill`), which the public SDK doesn't define. Both count as `ExitReason.MemoryLimit`;
reason 17 only from Android 17, since it means nothing documented on older releases.

To produce one, check that the device runs the limiter with `adb shell am memory-limiter status`; it
must report `enabled`. Some Android 17 builds ship without its config and report `disabled`, which a
`user` build can't change; use an Android 17 AVD with a Google APIs image instead. Then cap the
app's process and use it until Android stops it:

```sh
adb shell am memory-limiter manual -p $(adb shell pidof com.example.app) 300MB
```

Crossing the cap first only throttles the process; the kill comes once its anonymous memory plus
swap goes well past it, so lower the cap if nothing happens. `adb shell logcat -s MemoryLimiter`
shows the kill. The cap lasts for that process only.

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
An external source can declare an intentional opt-out with
`LogCapabilities(redactionConfigured = false, redactionOption = RedactionOption.NO_REDACTION, …)`.
The viewer uses the host's `MaterialTheme`; it does not depend on Lazulite or AltSea styles.

## Toolbar actions

Configure the built-in log Share and Clear buttons with `LogViewerOptions.Builder`. Both default
to visible; Clear is also hidden for read-only sources. An optional custom export button receives
`LogExport`, containing the actual temporary `File`, a FileProvider content `uri`, and `mimeType`:

```kotlin
val options = remember {
  LogViewerOptions.Builder()
    .showShareButton(false)
    .showClearButton(false)
    .exportAction(
      contentDescription = "Send logs to support",
      icon = { Icon(Icons.Outlined.Send, contentDescription = null) },
      onExport = { export ->
        // A suspend callback: use export.file for an upload, or export.uri for another app.
        // Move blocking file/network work to Dispatchers.IO.
        supportUploader.upload(export.file)
      },
    )
    .build()
}
LogViewer(source = capture, onBack = onBack, options = options)
```

The viewer creates the UTF-8 file off the UI thread, using exactly the selected session's visible
entries after search and level filters, including separate exception traces. No extra redaction is
applied to caller-provided sources. The callback runs on the UI coroutine and can suspend; repeated
export taps are disabled until it completes, exceptions appear in the viewer, and leaving the viewer
cancels the operation. Copy the file for durable work: it lives in private cache storage and exports
older than a day are deleted on a later export. When sending `uri` to another app, set
`FLAG_GRANT_READ_URI_PERMISSION` and include it in `ClipData` and `EXTRA_STREAM`.

You can also set defaults on `TimberLogCapture.Builder(context).viewerOptions(options).build()`;
`LogViewer` uses them automatically for that capture. An explicit `options` argument overrides
them and also works with any external `LogSource`. The custom button appears beside Share and Clear
on the Logs tab. These options control log toolbar actions; Status diagnostic sharing is separate.

The debug sample detects [LocalSend](https://localsend.org/) on each resume, loads its icon from
Android, and offers **Send logs with LocalSend** only when its text-file share activity is available.
It sends directly to LocalSend with a temporary read grant. Install LocalSend, open the sample's
Logs screen, and tap its icon to choose a nearby device. If LocalSend is removed, the button goes
away on resume; a removal between resume and a tap is reported in the viewer. Detection uses a
package-scoped visibility query in debug/R8 only; release has no viewer or LocalSend integration.

## API changes in 0.13.0

This pre-1.0 release narrows the public API. Migrate direct `TimberLogCapture(...)` construction to
`TimberLogCapture.Builder(context)…build()`. The clock override is an internal test seam.
For badges, replace `state.pendingExits.isNotEmpty()` with `capture.warnings` as shown above.
Advanced history consumers can read `(state as? ProcessExitState.Loaded)?.pending.orEmpty()`.

Viewer-only types and helpers (`LogLevel`, `LogFilter`, `matches`, `formatLogEntries`, `StatusMessage`,
`statusMessages`, and formatted exit labels/text) are now internal. Use the custom export callback
for the viewer's text format; use platform priority constants and raw exit fields for custom UI.
The default alert set is also internal; `alertOn(setOf(...))` explicitly selects the desired reasons.
The log-source contract and its data models, redactor, capture lifecycle, process-exit configuration,
history, acknowledgement and trace access remain public for custom integrations.

## Adaptive viewer layout

The viewer adapts to the width available inside its host, including split-screen windows.
Below 600 dp it uses the phone layout and initial-letter severity filters. From 600 dp the
filters show full level names and wrap as needed. At 840 dp and wider, a compact log list sits
beside a persistent detail pane; narrower windows use the details sheet.

The detail pane starts with “Select a log entry”. Selecting an entry pauses Follow, and new
entries never replace the selection. Resume Follow to scroll to new logs while keeping the
inspected entry pinned. Filtering can hide the selected row without closing its details;
switching sessions, clearing the session, or removal of that entry clears the selection.
Details include the full message, separate exception trace, tag filtering, and the shared
“Wrap long lines” preference. The tablet detail pane also shows the session and elapsed time
since the previous visible entry; the phone details sheet retains its existing content.
Elapsed times in the list and tablet detail pane compare adjacent visible entries.

## Try the sample

Run `./gradlew :logviewer:testDebugUnitTest :sample:assembleDebug :sample:assembleRelease`, then
install and launch the debug sample. On the home screen, toggle Share, Clear logs, LocalSend,
and process exit history before opening Logs. These choices survive rotation and returning from
the viewer; the LocalSend toggle is available only while LocalSend is installed. Generate logs,
open Logs, and use search, severity chips,
Follow, share, and Status. Tap **Crash app to test recovery**, relaunch the sample, open
Logs, and select the previous session. The sample's release variant contains no viewer dependency
or viewer screen.

For R8 integration coverage, see [Testing the optimized sample](docs/r8-testing.md).
