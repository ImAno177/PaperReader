# PaperReader contributor instructions

These rules apply to the whole repository. Prefer the smallest complete vertical slice, preserve
existing user work, and never present a stub as a production feature.

## Documentation index

- [`README.md`](README.md) - product summary, current feature list, screenshots, and build entry point.
- [`CHANGELOG.md`](CHANGELOG.md) - released changes and the current unreleased delta.
- [`docs/SPEC.md`](docs/SPEC.md) - current product behavior, domain rules, provider roles, and deferred scope.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) - module ownership, boundaries, extension lifecycle, and verification gate.
- [`docs/EXTENSIONS.md`](docs/EXTENSIONS.md) - source/theme SDK, signed-store schema, trust checks, and runtime limits.
- [`docs/WORKSPACE.md`](docs/WORKSPACE.md) - pinned checkouts and local verification of related repositories.
- [`docs/TESTING.md`](docs/TESTING.md) - local and connected test commands, coverage scope, CI artifacts, and release checks.
- [`docs/LIBRARY_TRANSFER.md`](docs/LIBRARY_TRANSFER.md) - backup, bibliography, and notes formats and limits.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) - branch, pull-request, required-check, and contribution workflow.
- [`SECURITY.md`](SECURITY.md) - private vulnerability reporting and security-sensitive areas.
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) - dependency, icon, and content-license attributions.

Read `docs/SPEC.md` and `docs/ARCHITECTURE.md` before changing domain models, boundaries,
providers, readers, or persistence. Read `docs/EXTENSIONS.md` before changing extension contracts.
Update the relevant document when a decision changes.

## Markdown documentation rules

- Write repository documentation in clear English. Keep UI copy in the resource files, not in prose
  documents.
- Use one H1 per file, sentence case for section headings, and a blank line around headings, lists,
  tables, and code fences. Keep paragraphs short enough to scan on GitHub.
- Start each document with its purpose and status. Describe implemented behavior as present tense;
  put planned, deferred, experimental, and removed behavior in clearly named sections.
- Do not describe mocks, screenshots, prototypes, or roadmap items as shipped features. Use explicit
  labels such as `Implemented`, `Planned`, `Deferred`, and `Deprecated` when status could be unclear.
- Use relative links for files in this repository and give every linked document one short description.
  Keep external links stable, remove tracking parameters, and never paste tool citation tokens.
- Prefer concrete names, versions, commands, file paths, and test evidence over broad claims. Avoid
  promotional filler, unexplained acronyms, em dashes, and placeholder text.
- Keep tables for compact comparisons. Use paragraphs for reasoning and lists for genuinely parallel
  items. Do not duplicate the same contract across multiple documents; link to its source of truth.
- CHANGELOG entry format: [`CONTRIBUTING.md`](CONTRIBUTING.md#development-workflow).
- Before opening a pull request, run the Markdown gate in [`docs/TESTING.md`](docs/TESTING.md), check
  links and code examples, remove stale claims, and confirm the documentation index still resolves.

## Project boundary

- Module ownership, dependency direction, and the `PaperReaderLogic` facade rule:
  [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#module-ownership).
- Provider implementations, signed stores, and extension contracts: [`docs/EXTENSIONS.md`](docs/EXTENSIONS.md).
- Submodules under `external/` keep their own remote, instructions, and release ownership:
  [`docs/WORKSPACE.md`](docs/WORKSPACE.md).

## Code organization

- Keep route wiring and adaptive shell policy in `PaperReaderApp.kt`; destination screens do not own
  navigation-controller orchestration.
- Put each root destination or More branch in `ui/screen/<Feature>Screen.kt`. Keep private cards,
  dialogs, labels, and presentation transformations beside their feature.
- Move a visual helper to `ui/components` only when at least two features share the same behavior and
  interface. Do not add pass-through wrappers, one-implementation interfaces, generic `Utils`, or
  speculative packages.
- Keep screen interfaces stable and state-driven. Navigation and Compose tests call the same root
  screen interface; do not add test-only seams.
- Kotlin file size limit and the split rule: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#code-organization).
- Mirror ownership in tests: presentation logic in `app/src/test`, Android semantics/geometry in
  `app/src/androidTest`, IPC/trust in `logic`, external provider parsers in the source repository, and
  persistence in `logic`.

## Git workflow

Branch names, commit and pull-request types, and one-concern branches follow
[`CONTRIBUTING.md`](CONTRIBUTING.md#development-workflow).

## Domain and persistence

Identity, persistence, and migration rules are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#domain-and-persistence) and
[`docs/SPEC.md`](docs/SPEC.md#identity-rules).

## Network, reader, and extension safety

- Never render unsanitized remote HTML, compile untrusted TeX in the app process, silently upload a
  paper, load third-party DEX, JAR, or JavaScript into the host, or expose host storage or database
  paths to an extension.
- Never invent extracted text, licenses, successful installs, or provider data. Preserve paper
  provenance, license, acquisition time, and the original-PDF fallback independently.
- Bind annotations to an exact canonical document hash and stable anchor. Never move them silently
  across a manifestation, sanitizer, renderer, parser, or PDF revision.
- Use official provider APIs with explicit rate and concurrency policy: [`docs/SPEC.md`](docs/SPEC.md#network-policy).
- Extension trust, install, theme, and Binder rules: [`docs/EXTENSIONS.md`](docs/EXTENSIONS.md).

## Development and verification

- Gradle wrapper, JDK, SDK, and the local gate command: [`docs/TESTING.md`](docs/TESTING.md#local-gate).
- Keep the smallest stable contract test. Remove obsolete, tautological, and duplicate tests in the
  same change. Test policy: [`docs/TESTING.md`](docs/TESTING.md#test-case-policy).
- Never weaken or baseline `LogicBoundaryTest`, add a lint baseline, or use a destructive Room
  migration. Boundary checks: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#verification).
- Do not add analytics, proprietary SDKs, credentials, private endpoints, or redistribution claims
  without an explicit product and license review. Do not add speculative modules.
- Preserve coroutine cancellation; do not block the main thread with database, network, parsing,
  hashing, or Binder work.
- Connected-test reporting and pull-request contents: [`CONTRIBUTING.md`](CONTRIBUTING.md#development-workflow).

## Local device and environment

- Connected tests: run `hostConnectedTest` with `-PpaperReaderConnectedTestApplicationIdSuffix=.uitest`.
  Without the suffix the run uninstalls `dev.paperreader.app` and wipes the user's library. The build
  refuses `:app` connected tasks that lack the suffix. Commands and results: [`docs/TESTING.md`](docs/TESTING.md#local-gate).
- Emulator: reuse the connected emulator. Never wipe, factory-reset, or start a second instance.
- Debug host with the local arXiv source: install with `:app:installDebug` and the `paperReaderDevSource*`
  properties from [`docs/EXTENSIONS.md`](docs/EXTENSIONS.md#local-host-development):
  `paperReaderDevSourceProviderId=arxiv`, `paperReaderDevSourceCapabilities=search,details,pdf_link,readable_document`,
  `paperReaderDevSourceIdentifierTypes=arxiv`, `paperReaderDevSourceMinimumIntervalMillis=3000`, and
  `paperReaderDevSourceSignerSha256` set to the debug certificate SHA-256, which
  `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` prints.
- Build `external/sources` with `-PpaperReaderHostSignerSha256=<same digest>` and
  `-PpaperReaderSdkPath=<host checkout>`. The host and external builds must use the same AGP version.
- adb from Git Bash needs `MSYS_NO_PATHCONV=1`. Capture screenshots with `screencap -p /sdcard/x.png`,
  then `adb pull`; in PowerShell, never redirect `exec-out`. Tap elements by `uiautomator` text, not coordinates.
- Before trusting a light or dark screenshot, check `theme_mode` in
  `files/datastore/paper-reader-settings.preferences_pb`, and restore any setting you change.
- Neobrutalism colors: source and contrast targets are in
  [`docs/SPEC.md`](docs/SPEC.md#information-architecture). Verify light and dark on the emulator.
- This machine sets `core.autocrlf=true`. Write generated files with LF. Never use `git commit -a`,
  which would stage `external/` submodule pointers; stage paths explicitly.
