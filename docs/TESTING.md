# Testing

Status: current verification contract. This file is the source of truth for local commands, test
ownership, CI responsibilities, coverage evidence, and release verification.

## Test ownership

- JVM tests own deterministic domain, parser, repository, presentation, and state-transition logic.
- Connected Android tests own Room, Binder, WebView, Compose semantics and geometry, and Android
  lifecycle behavior.
- Local fixtures and MockWebServer own provider/network test data. Live provider APIs and Play Protect
  are manual release checks, never deterministic test dependencies.
- External provider parser, signed-APK, registry, and SBOM checks belong to the source-extension
  repository, not this host repository.

## Documentation check

The repository configuration owns the included files and lint rules:

```powershell
npx --yes markdownlint-cli2
```

## Local host gate

Use the Gradle wrapper, JDK 21, and Android SDK 36/36.1:

```powershell
.\gradlew.bat hostUnitTest hostLint :app:assembleDebug
```

`hostUnitTest` and `hostLint` are the canonical aggregate tasks for the three production modules.
Do not copy their module task lists into another workflow or document.

## Connected Android gate

Reuse the declared API 36 emulator without wiping it:

```powershell
.\gradlew.bat hostConnectedTest `
  "-PpaperReaderConnectedTestApplicationIdSuffix=.uitest"
```

The suffix installs an isolated debug test host when a production-signed app is already present. It
does not replace the release app or delete its data. Connected XML results are written below each
module's `build/outputs/androidTest-results/connected` directory.

## Coverage

Choose the report that matches the verification already needed:

```powershell
# JVM tests and unit coverage for all production modules
.\gradlew.bat coverageReport

# JVM plus connected Android tests and merged coverage for all production modules
.\gradlew.bat coverageConnectedReport `
  "-PpaperReaderConnectedTestApplicationIdSuffix=.uitest"
```

These report tasks already own the tests they measure. Do not run `hostUnitTest` or
`hostConnectedTest` first solely to generate coverage.

Reports are XML, CSV, and HTML below each module's `build/reports/jacoco` directory. Only generated
Android classes, Room implementation classes, serializers, Composable singleton holders, and AIDL
stubs are filtered. Handwritten transport, repository, parser, and task code stays in the
denominator. A coverage claim must cite the exact XML counters and test-result files.

`:extension-api` enforces 100% merged line and branch coverage because it is a small, versioned public
contract. `:logic` and `:app` publish reports without a percentage gate until their Android seams and
connected device are deterministic in CI. Never add broad exclusions or tautological tests to make a
percentage pass.

## CI ownership

Each verification gate has one owner. Security and release jobs do not repeat host tests.

| Owner | Responsibility | Explicitly excluded |
| --- | --- | --- |
| [`android-ci.yml`](../.github/workflows/android-ci.yml) / `dependency-review` | Pull-request dependency diff | Gradle tests and builds |
| `android-ci.yml` / `documentation` | Markdown lint | Gradle tests and builds |
| `android-ci.yml` / `quality-and-apk` | `coverageReport`, `hostLint`, debug and unsigned release APKs, build SBOM | Connected tests, signed release, security analysis |
| `android-ci.yml` / `secret-scan` | Full-history Gitleaks scan | Gradle tests and builds |
| `android-ci.yml` / `mobsfscan` | MobSF source analysis and SARIF upload | Gradle tests, APK analysis, CodeQL |
| [`codeql.yml`](../.github/workflows/codeql.yml) / `analyze` | Java/Kotlin extraction and CodeQL analysis | Unit tests, lint, distributable APK |
| [`release.yml`](../.github/workflows/release.yml) / `release` | Green-commit check, release lint, signed APK, signature, release SBOM, publish | Unit tests and debug lint |

Android CI and CodeQL both compile the debug variant for different outputs: Android CI publishes the
APK, while CodeQL requires its own instrumented extraction build. They do not share test ownership.
The release workflow requires a successful Android CI run for the exact commit before restoring
signing material, so it does not repeat the host unit or debug-lint gates.

GitHub CodeQL Advanced runs for pushes to `main`, pull requests targeting `main`, a weekly schedule,
and manual dispatch. Its manual extraction build and `security-extended` query suite are defined only
in `codeql.yml`; GitHub Default setup must remain disabled.

MobSF scans Kotlin, Java, and Android XML as source and uploads SARIF to Code Scanning. `--no-fail`
keeps the source scan informational while findings are triaged; it is not an APK security verdict.

The Gradle cache has one writer: `quality-and-apk` on a successful push to `main`. Pull requests,
CodeQL, and release runs consume that cache read-only. No other job writes Gradle User Home cache
entries.

## Test-case policy

Prefer a focused test at the narrowest owning boundary. A behavior should not be repeated as JVM,
connected, and end-to-end tests unless each layer exercises a different platform risk. Migration and
algorithmic precedents were reviewed against Mihon's pinned
[`MigratorTest`](https://github.com/mihonapp/mihon/blob/497e2662565c2727113125493a324465b9bc56ff/app/src/test/java/mihon/core/migration/MigratorTest.kt)
and [`build.yml`](https://github.com/mihonapp/mihon/blob/497e2662565c2727113125493a324465b9bc56ff/.github/workflows/build.yml).

Coverage is review evidence, not a reason to test getters or implementation details. Preserve
coroutine cancellation, use deterministic clocks/dispatchers where required, and keep live network
verification outside the automated suite.

## Release verification

The product-level release checklist is canonical in
[`SPEC.md`](SPEC.md#release-acceptance). Record exact commands, device/API, test-result paths, live
provider samples, extension-install outcomes, signature verification, and Play Protect status in the
pull request or release handoff. Do not store an undated "current branch passes" claim in this policy
file.
