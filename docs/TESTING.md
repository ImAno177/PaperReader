# Testing

PaperReader maps tests to production boundaries. Coverage counters support review; they do not define
product readiness. This file describes the current verification contract and the evidence expected in
a pull request.

## Current verification baseline

- The local host gate passes on the current branch: unit tests, lint, and debug APK assembly for all
  three modules.
- The Google-to-arXiv handoff parser passes four connected Android tests on the declared API 36
  emulator, including `/html/` URL version parsing and rejection of unrelated VIEW links.
- Release readiness still requires the full connected suite, live-provider audit, extension install
  flows, and Play Protect review listed below.

## Local gate

Run the host gate with the Gradle wrapper, JDK 21, and Android SDK 36/36.1:

```powershell
.\gradlew.bat hostUnitTest hostLint :app:assembleDebug
```

For Android-runtime, Room, reader, or UI changes, reuse the declared API 36 emulator (`emulator-5554`)
without wiping it:

```powershell
.\gradlew.bat :extension-api:connectedDebugAndroidTest
.\gradlew.bat :logic:connectedDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -PpaperReaderConnectedTestApplicationIdSuffix=.uitest
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

## CI responsibilities

PaperReader separates dependency review, tests, lint, artifacts, and security checks. Each workflow
owns one gate so a test command is not copied into security or release jobs.
The root Gradle tasks `hostUnitTest` and `hostLint` are the single command definitions for the host
unit-test and lint gates.

| Workflow or job | Owns | Does not repeat |
| --- | --- | --- |
| `android-ci.yml` / `dependency-review` | Pull-request dependency diff | Gradle tests and builds |
| `android-ci.yml` / `quality-and-apk` | One Gradle invocation for host tests, debug lint, and the debug APK on pull requests; main and manual runs also produce JaCoCo, an unsigned release APK, and an SBOM | CodeQL extraction, MobSF, secrets, dependency review, connected tests |
| `android-ci.yml` / `documentation` | Markdown lint | Gradle tests or builds |
| `android-ci.yml` / `secret-scan` | Full-history Gitleaks scan | Gradle tests and builds |
| `android-ci.yml` / `mobsfscan` | MobSF source scan and SARIF upload | Gradle tests or builds |
| `codeql.yml` | Java/Kotlin CodeQL extraction and analysis | Unit tests and lint; its debug build exists only to provide extraction input |
| `release.yml` | Green-commit check, release lint, signed APK, signature verification, release SBOM, and publishing | Unit tests and `lintDebug`, which belong to the green Android CI run |

The release workflow requires a successful `android-ci.yml` run for the exact commit before it
restores signing material. This keeps release verification tied to the same host gate used for pull
requests and main, without running that gate a second time.

Pull requests intentionally skip coverage packaging, the unsigned release build, and SBOM generation.
Those artifacts do not change the review verdict and remain available from main, manual, and release
runs. Gradle receives all tasks for a run in one invocation, so shared compilation and unit-test work
is executed once. Connected tests remain a local release-readiness gate on the declared emulator; CI
does not claim device coverage that it does not run.

GitHub CodeQL Advanced scans the Java/Kotlin build on every pull request and main push, with a weekly
scheduled security-extended analysis. The repository intentionally uses Advanced setup: `codeql.yml`
is the source of truth, while GitHub's Default setup remains disabled so it cannot replace that pinned
build-and-query policy. PaperReader has no formatter plugin and keeps Room schema/migration coverage
in `logic` Android tests. Release publishing remains in the repository's own `release.yml` workflow.

Android CI also runs the GitHub-verified `MobSF/mobsfscan` source scanner for Kotlin, Java, and
Android XML. It uploads a SARIF report to GitHub Code Scanning. The scan uses `--no-fail` so existing
findings remain visible for triage without turning an informational baseline into a false-green
security waiver; no findings are suppressed by repository configuration.

Gradle's enhanced cache has one writer: a successful `push` to `main` in `android-ci.yml`. Pull
requests, CodeQL, and manual release runs are read-only consumers. This prevents each security scan,
release, or review commit from creating a duplicate dependency/transforms cache; successful writes also
run Gradle's built-in cleanup. The policy follows Gradle's `cache-read-only` guidance and GitHub's
branch-scoped cache model.

## Test-case policy

The repository uses a focused suite rather than a blanket 100% target. Fast JVM tests cover
deterministic domain, provider, parser, and state transitions; connected tests cover Room, Binder,
WebView, Compose semantics, and real Android lifecycles. A coverage percentage is evidence for review,
not a reason to add getter-only or tautological tests. The extension API keeps its stricter 100%
line/branch guard because that small module is a versioned public contract; `logic` and `app` remain
report-only until their Android framework seams can be measured deterministically.

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
