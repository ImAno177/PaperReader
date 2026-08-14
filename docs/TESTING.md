# Testing

PaperReader keeps test scope aligned with the production boundary instead of treating a generated
counter as a product guarantee.

## Local gate

Run the host gate with the Gradle wrapper, JDK 21, and Android SDK 36/36.1:

```powershell
.\gradlew.bat :extension-api:testDebugUnitTest :extension-api:lintDebug `
  :logic:testDebugUnitTest :logic:lintDebug `
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

For Android-runtime, Room, reader, or UI changes, reuse the declared API 36 emulator (`emulator-5554`)
without wiping it:

```powershell
.\gradlew.bat :extension-api:connectedDebugAndroidTest
.\gradlew.bat :logic:connectedDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -PpaperReaderConnectedTestApplicationIdSuffix=.uitest `
  -PappVersionCode=3
```

The isolated app suffix avoids replacing a production-signed installation. Connected XML results are
kept under each module's `build/outputs/androidTest-results/connected` directory.

## Coverage

Generate unit and merged reports after the relevant tests:

```powershell
.\gradlew.bat :extension-api:jacocoDebugUnitTestReport :logic:jacocoDebugUnitTestReport :app:jacocoDebugUnitTestReport
.\gradlew.bat :logic:jacocoDebugCombinedReport
```

Reports are XML, CSV, and HTML under `build/reports/jacoco`. The merged report combines deterministic
JVM tests with connected Android coverage. Only generated Android classes, Room implementation
classes, serializers, Composable singleton holders, and AIDL stubs are filtered; handwritten
transport, repository, parser, and task code stays in the denominator. A coverage claim must cite
the exact XML counters and test result files. Do not add a broad exclusion or a tautological test to
make a percentage pass.

`:extension-api` currently enforces 100% merged line and branch coverage. `:logic` and `:app` publish
reports while their Android/framework seams are being closed; a threshold is added only when the
denominator and connected test device are deterministic in CI.

## CI model

The workflow follows Mihon's useful build shape: dependency review on pull requests, a dedicated
unit-test step, failure-only test report artifacts, lint, release/debug APK assembly, and retained
build artifacts. PaperReader additionally publishes JaCoCo unit reports and an SPDX SBOM. Mihon's
reference workflow is [`build.yml`](https://github.com/mihonapp/mihon/blob/main/.github/workflows/build.yml).

Tests use local fixtures and MockWebServer for provider/network behavior. Live provider APIs and
Play Protect are release smoke checks, never deterministic unit-test dependencies.
