# Testing

PaperReader maps tests to production boundaries. Coverage counters support review; they do not define
product readiness. This file describes the current verification contract and the evidence expected in
a pull request.

## Local gate

Build and dependency versions are pinned in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml).
Use the Gradle wrapper with JDK 21, Android SDK Platform 37.0, and Build-Tools 36.1.0:

```powershell
.\gradlew.bat hostUnitTest hostLint :app:assembleDebug
```

For Android-runtime, Room, reader, or UI changes, reuse an already connected emulator without wiping
it. Record its AVD name, Android version, and API level with the results:

```powershell
.\gradlew.bat hostConnectedTest '-PpaperReaderConnectedTestApplicationIdSuffix=.uitest'
```

The isolated app suffix avoids replacing a production-signed installation. Connected XML results are
kept under each module's `build/outputs/androidTest-results/connected` directory.

For readable-paper asset changes, the local audit must also confirm that the fresh APK can open
Attention, CGP-Tuning, LoRA, Llama, ResNet, and InstructGPT, including figure captions, reader back,
find, contents, cache reopen, offline reopen, and one self-contained HTML export. Inspect local
app-private evidence for separate body/asset files and SHA-256-validated asset counts. A paper's
image count is not capped; record any per-asset, SVG-complexity, cache-quota, or network failure as
an explicit warning. Include a large image-heavy paper and a self-contained SVG with embedded raster
data in the asset audit, and confirm transient 429/unavailable responses use bounded retries before
being reported. Do not add or modify test sources for this audit, and do not use GitHub as the test
runner.

## Local verification (2026-11-09)

The existing `Pixel_10` AVD (`emulator-5554`, Android 17, API 37, x86_64) passed all 155 connected
tests: extension API 15, logic 50, and app 90. The local host gate passed all 370 JVM tests:
extension API 15, logic 267, and app 88. There were no failures, errors, or skipped cases in these
final runs. Host lint and debug assembly also passed. No new merged-coverage result is claimed.

The reviewed normal debug APK has SHA-256
`5E5A29CB40739D0FAD1B2652FE6458A7F36236931D9C788508ACC3EA7E0A8B42`.
It was installed without clearing the existing library. Manual checks covered:

- Live arXiv exact search, retained search state across tabs, and a preview action visible immediately
  at both 100% and 150% system font size.
- Neobrutalism light/dark surfaces, full Library grid titles, collapsed CARE-LoRA author details,
  single source-supplied list markers, and Contents navigation that leaves Introduction below the
  reader toolbar. A separate browser layout check covered multi-paragraph and nested ordered lists.
- A useful search failure with Wi-Fi and mobile data disabled, followed by a cached CARE-LoRA reopen
  with the network still disabled.
- An isolated `.uitest` restore from a previously exported three-paper, one-PDF archive. Killing the
  background process during the document picker still delivered its result. Killing it again during
  preview restored the same confirmation after relaunch; confirmation restored all three papers and
  the three-page PDF. The result scrolled into view, and the restored PDF opened successfully.

Network, font scale, color mode, and Library layout were returned to their original settings. Only
the existing API 37 emulator was used. TalkBack, other Android versions, and a production-signed
host/source pair were not verified. Live search used the documented local certificate configuration
with the API 2 arXiv source; this is not evidence that the public signed API 2 store has been released.

The [extension workspace gate](WORKSPACE.md#verify-android-compatibility) passed JVM tests, debug
lint, and debug assembly in the three pinned Android extension repositories. Markdown lint and
GitHub Actions syntax validation also passed.

The README screenshots show the reviewed Library, live search, and cached reader on this emulator.
They are runtime captures of the unreleased branch, not design mockups or a signed-release claim.

## Historical local verification (2026-11-06)

The existing `Pixel_10` AVD (`emulator-5554`, Android 17, API 37, x86_64) passed 347 JVM and
144 connected tests with no failures, errors, or skipped cases. Debug/release lint and APK assembly
passed. Merged extension API coverage remained 690/690 lines and 468/468 branches.

The HTML-export restoration regression failed before the fix and passed after it. A separate manual
SAF export stopped the background host process while the save picker remained open. Saving relaunched
the host and wrote a complete 2,034,632-byte HTML file with eight embedded images and the original
cached document hash. HTML figures and the original PDF also reopened with no active network.

Manual checks also covered PDF import with a title preserved through rotation, three-page PDF search,
page navigation, bookmarks, progress reopening, history, collection assignment, metadata backup and
exact-match restore, theme selection, and More at 150% system font size. Network and display settings
were restored after these checks.

Live arXiv checks used source commit `27616dc1044e09085fc6553fea850702a9e90870` with the documented
[local development configuration](EXTENSIONS.md#local-host-development). The published source v0.1.4
rejects the local debug host certificate and does not advertise readable HTML. Production signer
checks and signed-store records were unchanged; this audit does not verify a signed release pair.

## Historical local audit (2026-09-07)

The release APK was assembled, installed, and exercised only on the shared API 36 emulator. The
host gate and source gate both passed locally. The final host debug artifact is
`app/build/outputs/apk/debug/app-debug.apk`; its SHA-256 is
`6DD91CD086B5D5E92E069AC49AD791C45EDE496B56196A7D5A15A69B27C0EAA1`.

The manual matrix covered:

- Library, Search, Paper Detail, More, Stats, Appearance, Download queue, and predictable branch Back.
- Appearance with the unchanged Neobrutalism preset plus Ocean, Forest, Violet, Rose, and Sunset;
  Light and Dark mode selection remained independent.
- Attention search with 20 results, detail actions, a live download percentage, queue completion,
  and the reader's find/contents/Back controls.
- Attention and CGP-Tuning readable documents with visible figure assets and captions. The broader
  local paper audit also covered LoRA, Llama 2, ResNet, and InstructGPT for HTML/PDF/cache paths.
- The compact 540×1200 viewport was also checked; More preference titles and summaries ellipsize
  without splitting words, while the original Neobrutalism visual tokens remain unchanged.
- Reader chrome hiding was checked in Light/Neobrutalism; hidden toolbar/provenance slots now leave
  no canvas gap or document reflow, and a document tap restores both controls. The WebView bounds
  remain unchanged while the overlay is hidden during a slow swipe.

Fresh emulator captures are in `docs/screenshots/release-2026-09-07-*.png`, including the refreshed
More and Stats surfaces. They are runtime evidence, not mocks. The readable pipeline still has separate HTML/document and same-document asset
lanes and no arbitrary image-count limit; per-asset bytes, SVG complexity, manifest size, retries,
and cache quotas remain the safety bounds.

The 2026-09-07 app XML result reports `85` tests, `0` failures, `0` errors, and `0` skipped. The
share/import/status lifecycle flow and `MainNavigationAndroidTest.everyMoreBranchHidesPrimaryNavigationAndReturnsToTheHub`
both pass on the API 36 emulator.
Android API 36 also logs the framework warning for a malformed single-stream `ACTION_SEND` extra;
the production decoder accepts a single `Uri`, an `ArrayList`, or `ClipData` and handles the flow.
The warning does not affect the green suite, and no GitHub-only test was added.

## Coverage

Generate deterministic JVM coverage, or run connected tests and enforce merged coverage:

```powershell
.\gradlew.bat coverageReport
.\gradlew.bat coverageConnectedReport '-PpaperReaderConnectedTestApplicationIdSuffix=.uitest'
```

Reports are XML, CSV, and HTML under `build/reports/jacoco`. The merged report combines deterministic
JVM tests with connected Android coverage. Only generated Android classes, Room implementation
classes, serializers, Composable singleton holders, and AIDL stubs are filtered; handwritten
transport, repository, parser, and task code stays in the denominator. A coverage claim must cite
the exact XML counters and test result files. Do not add a broad exclusion or a tautological test to
make a percentage pass.

`:extension-api` currently enforces 100% merged line and branch coverage. `:logic` and `:app` publish
reports while their Android/framework seams are being closed. Combined reports fail when connected
coverage or compiled production classes are missing. Do not treat unit-only execution as merged
coverage. Each merged report depends on its connected-test task, so tests complete before the report
reads coverage data. Use the same sources, build variant, and isolated test package for both.

## CI responsibilities

PaperReader separates dependency review, tests, lint, artifacts, and security checks. Each workflow
owns one gate so a test command is not copied into security or release jobs.
The root Gradle tasks `hostUnitTest` and `hostLint` are the single command definitions for the host
unit-test and lint gates. `hostLint` first runs `checkNoSourceComments`, which rejects comments
in Kotlin, Java, AIDL, Android XML, Gradle, and ProGuard sources. All three Android workflows share
[the Android setup action](../.github/actions/setup-android/action.yml), which reads tool versions
from the Gradle catalog. Third-party actions remain pinned by commit.

| Workflow or job | Owns | Does not repeat |
| --- | --- | --- |
| `android-ci.yml` / `dependency-review` | Pull-request dependency diff | Gradle tests and builds |
| `android-ci.yml` / `quality-and-apk` | One Gradle invocation for host tests, debug lint, and the debug APK on pull requests; main and manual runs also produce JaCoCo, an unsigned release APK, and an SBOM | CodeQL extraction, MobSF, secrets, dependency review, connected tests |
| `android-ci.yml` / `documentation` | Markdown and GitHub Actions lint | Gradle tests or builds |
| `android-ci.yml` / `secret-scan` | Full-history Gitleaks scan | Gradle tests and builds |
| `android-ci.yml` / `mobsfscan` | MobSF source scan and SARIF upload | Gradle tests or builds |
| `codeql.yml` | Java/Kotlin CodeQL extraction and analysis | Unit tests and lint; its debug build exists only to provide extraction input |
| `release.yml` | Green-commit check, release lint, signed APK, signature verification, release SBOM, and publishing | Unit tests and `lintDebug`, which belong to the green Android CI run |

Releases must be dispatched from `main` with a valid semantic version and an Android version code
from 1 to 2100000000. The release workflow requires a successful `android-ci.yml` main-branch push
for the exact commit before it restores signing material. Version and signing properties reach
Gradle through environment variables; the keystore is removed even when a later step fails. This keeps release verification tied to the same host gate used for pull
requests and main, without running that gate a second time. Failed host checks upload JUnit XML, unit-test HTML, and
lint reports. APK uploads disable redundant ZIP compression.

### Host and source release order

The host workflow creates a GitHub prerelease and leaves the current stable release marked latest.
It does not automatically promote the candidate. This allows the source workflow to verify the
signed candidate before publishing a new contract channel.

For an extension contract upgrade:

1. Merge the reviewed host and source changes and wait for their exact main commits to pass CI.
2. Build the host candidate from that main commit. Set the source repository's
   `PAPERREADER_SDK_REF` variable to the same immutable host commit.
3. Dispatch the source release with that SDK commit, the candidate's tag, and a version code above
   all published source and catalog versions. A stale source checkout must be redispatched.
4. Verify the signed API 2 catalog and its APK URLs, hashes, sizes, certificates, and descriptors.
   On a fresh test installation, refresh the store, install a source through Android confirmation,
   search, and open a paper. Also verify upgrading an existing installation and cached offline reading.
5. Promote the exact tested host candidate only after those checks succeed:

   ```powershell
   gh release edit $env:PAPERREADER_RELEASE_TAG --repo ImAno177/PaperReader --prerelease=false --latest
   ```

The API 2 public catalog is not yet published. A candidate cannot provide official source installation
on a fresh device until that catalog exists. Local development-source smoke tests do not prove this
public delivery path; never promote a candidate using only those results.

Pull requests intentionally skip coverage packaging, the unsigned release build, and SBOM generation.
Those artifacts do not change the review verdict and remain available from main, manual, and release
runs. Gradle receives all tasks for a run in one invocation, so shared compilation and unit-test work
is executed once. Connected tests remain a local release-readiness gate on the declared emulator; CI
does not claim device coverage that it does not run.

Pull requests that change only root Markdown or files under `docs/` also skip Android setup and
Gradle in the existing build job. Documentation, dependency review, and security checks still run;
the workflow and check names remain present. The scope check compares the complete pull-request
diff, including deletions and both sides of renames. Missing or unreadable diff data runs the full
gate. Source, asset, dependency, build, and workflow changes always run it, as do all main pushes
and manual runs, so release eligibility still requires full verification of the exact main commit.

GitHub CodeQL Advanced scans the Java/Kotlin build on every pull request and main push, with a weekly
scheduled security-extended analysis. The repository intentionally uses Advanced setup: `codeql.yml`
is the source of truth, while GitHub's Default setup remains disabled so it cannot replace that pinned
build-and-query policy. PaperReader has no formatter plugin and keeps Room schema/migration coverage
in `logic` Android tests. Release publishing remains in the repository's own `release.yml` workflow.

Android CI also runs the GitHub-verified `MobSF/mobsfscan` source scanner for Kotlin, Java, and
Android XML. It uploads a SARIF report to GitHub Code Scanning. The scan uses `--no-fail` so existing
findings remain visible for triage without turning an informational baseline into a false-green
security waiver; no findings are suppressed by repository configuration.

Only the `android-ci.yml` gate writes Gradle's enhanced cache: `main` pushes refresh the shared
cache, and same-repository pull requests write a PR-scoped cache that later pushes to the same pull
request restore. Fork pull requests, CodeQL, and release runs are read-only consumers. Work lands
through long-lived pull requests, so a main-only writer left the cache evicted after GitHub's
seven-day idle limit and every run rebuilt from empty. Successful writes run Gradle's built-in
cleanup, and GitHub evicts least-recently-used entries at the repository quota.

## Test-case policy

The repository uses a focused suite rather than a blanket 100% target. Fast JVM tests cover
deterministic domain, provider, parser, and state transitions; connected tests cover Room, Binder,
WebView, Compose semantics, and real Android lifecycles. A coverage percentage is evidence for review,
not a reason to add getter-only or tautological tests. The extension API keeps its stricter 100%
line/branch guard because that small module is a versioned public contract; `logic` and `app` remain
report-only until their Android framework seams can be measured deterministically.

Tests use local fixtures and MockWebServer for provider/network behavior. Live provider APIs and
Play Protect are release smoke checks, never deterministic unit-test dependencies.
