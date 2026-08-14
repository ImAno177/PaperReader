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
  -PappVersionCode=4
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

The workflow follows the parts of Mihon's build shape that fit this repository: dependency review on
pull requests, a dedicated `testDebugUnitTest` step, failure-only test-report artifacts, lint checks,
and APK artifacts. PaperReader additionally publishes JaCoCo unit reports and an SPDX SBOM. Mihon
also runs Spotless and SQLDelight migration verification; PaperReader has no formatter plugin and
keeps Room schema/migration coverage in `logic` Android tests, so those are reported separately
instead of pretending an equivalent task exists. Mihon's reference workflow is
[`build.yml`](https://github.com/mihonapp/mihon/blob/main/.github/workflows/build.yml); its tag-based
APK publishing is in [`release.yml`](https://github.com/mihonapp/mihon/blob/main/.github/workflows/release.yml).

## Test-case policy

Mihon's repository uses a deliberately focused suite rather than a blanket 100% target: migration
behavior lives in [`MigratorTest`](https://github.com/mihonapp/mihon/blob/main/app/src/test/java/mihon/core/migration/MigratorTest.kt),
algorithmic edge cases in core/common, and domain invariants/interactors under `domain/src/test`.
PaperReader follows the same split: fast JVM tests cover deterministic domain, provider, parser, and
state transitions; connected tests cover Room, Binder, WebView, Compose semantics, and real Android
lifecycles. A coverage percentage is evidence for review, not a reason to add getter-only or
tautological tests. The extension API keeps its stricter 100% line/branch guard because that small
module is a versioned public contract; `logic` and `app` remain report-only until their Android
framework seams can be measured deterministically.

Tests use local fixtures and MockWebServer for provider/network behavior. Live provider APIs and
Play Protect are release smoke checks, never deterministic unit-test dependencies.

## Release smoke record (2026-08-14)

The signed [v0.1.3 release](https://github.com/ImAno177/PaperReader/releases/tag/v0.1.3) was exercised
on `emulator-5554` (`covaigay_api36(AVD) - 16`, API 36) with the official source extensions installed.
The host is commit `7d7d176`; the APK is versionCode 4. Results were:

- `arXiv:1706.03762v7` searched, opened in detail, saved, and loaded as verified/cached HTML. The
  mobile reader now renders each author as a vertical block with readable affiliation/email lines;
  no raw `footnotemark` payload appeared. Reader search and Paper contents opened successfully.
- `arXiv:2501.04510v2` searched, opened in detail, saved, and loaded as verified/cached HTML. The
  known conversion-artifact warning was surfaced without blocking reading.
- A Crossref/Europe PMC DOI lookup (`10.1038/s41586-020-2649-2`) returned live metadata for
  *Array programming with NumPy*. An unauthenticated Semantic Scholar rate limit was surfaced as
  an unavailable source instead of fabricated results.
- The connected Android suite completed `78` tests with `0` failures, `0` errors, and `0` skipped.
  The local JVM suites completed `11` extension-api, `245` logic, and `77` app test cases with no
  failures or errors.

The README reader image is the same v0.1.3 smoke capture, so documentation reflects the shipped
mobile layout rather than a mock or stale render. Play Protect evidence remains a device-installed-
set verdict; it is not an upload API attestation.
