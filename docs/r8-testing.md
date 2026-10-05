# Testing the optimized sample

The sample's `r8` build type runs the debug sample screen with code optimization,
obfuscation, and resource shrinking enabled. It consumes the library's release variant,
uses debug signing, and has the separate application ID
`com.pedronveloso.logviewer.sample.r8`. The regular sample release continues to exclude the viewer.

The `:r8test` module uses UI Automator from its own process. It has no dependency on the
library and no references to app classes, so test code cannot introduce extra keep roots.
The test verifies capture and redaction, recovery after a process restart, search and
severity filtering, and sharing through the real FileProvider URI grant to a receiver in
the test app. It clears only the isolated `r8` app's data before running.

With an emulator or device running in English, run:

```sh
./gradlew :r8test:connectedR8AndroidTest
```

When multiple devices are connected, set `ANDROID_SERIAL` to the device to test, for example
`ANDROID_SERIAL=emulator-5554 ./gradlew :r8test:connectedR8AndroidTest`.

To build the APKs and inspect the rule configuration without a device:

```sh
./gradlew :sample:assembleR8 :r8test:assembleR8 :sample:analyzeR8R8Config
```

The optimized APK is in `sample/build/outputs/apk/r8/`; its mapping and merged rules
are in `sample/build/outputs/mapping/r8/`. The analyzer report is in
`sample/build/reports/r8/`. Device test results are in `r8test/build/reports/androidTests/`.

The library currently needs no custom consumer keep rules. Add targeted rules only for
demonstrated reflection or JNI entry points, and rerun this test after changing them.
Do not preserve the whole library package or duplicate dependency consumer rules.

CI builds both integration APKs, analyzes the R8 configuration, and lints the integration
variant. Download its `r8-reports` artifact to review the analyzer results.
The device test must also pass before shipping changes affecting R8 compatibility;
the build-only CI checks cannot detect runtime failures.
