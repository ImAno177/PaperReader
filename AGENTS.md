# PaperReader contributor instructions

These rules apply to the whole repository. Prefer the smallest complete vertical slice, preserve
existing user work, and never present a stub as a production feature.

## Documentation index

- [`README.md`](README.md) — product overview, feature highlights, screenshots, and build entry point.
- [`docs/SPEC.md`](docs/SPEC.md) — product scope, domain decisions, provider roles, reader requirements, and release acceptance.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module ownership, supported boundaries, extension lifecycle, and verification gate.
- [`docs/EXTENSIONS.md`](docs/EXTENSIONS.md) — source/theme SDK, signed-store schema, trust checks, and runtime limits.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — branch, pull-request, required-check, and contribution workflow.
- [`SECURITY.md`](SECURITY.md) — private vulnerability reporting and security-sensitive areas.
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — dependency, icon, and content-license attributions.

Read `docs/SPEC.md` and `docs/ARCHITECTURE.md` before changing domain models, boundaries,
providers, readers, or persistence. Read `docs/EXTENSIONS.md` before changing extension contracts.
Update the relevant document when a decision changes.

## Project boundary

```text
:app ---> :logic ---> :extension-api <--- external source/theme APKs
```

- `:app` owns Compose, Activities, navigation, view-model state, accessibility, English strings,
  themes, icons, extension-install UI, and presentation mapping.
- `:logic` owns immutable domain models, repository ports, Room, provider policy, reader artifacts,
  task state, backup, signed-store verification, and extension trust/runtime seams.
- Provider implementations do not live in this repository. Official Semantic Scholar, Crossref,
  arXiv, and Europe PMC APKs live in `ImAno177/PaperReader-sources`, depend only on the published
  `:extension-api`, and are discovered through its pinned signed store.
- `:extension-api` owns only the published, versioned, bounded AIDL/data contract used by the host
  and separately built extension APKs.
- UI code consumes one application-scoped `PaperReaderLogic` facade. It must not import Room DAOs,
  concrete repositories, HTTP clients, provider parsers, extraction internals, or plugin binders.
- `:logic` must not import Compose, Android UI classes, navigation, or visual resources.
- Never weaken or baseline `LogicBoundaryTest` to make a build pass.

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
- Keep production Kotlin files below 600 lines. Before adding to a larger file, split at an existing
  feature or lifecycle seam without growing the public/package interface. Generated files are exempt;
  other exceptions require a rationale in `docs/ARCHITECTURE.md`.
- Mirror ownership in tests: presentation logic in `app/src/test`, Android semantics/geometry in
  `app/src/androidTest`, IPC/trust in `logic`, external provider parsers in the source repository, and
  persistence in `logic`.

## Git workflow

- Follow Paperlib's typed branch convention: `<type>/<kebab-case-summary>`, optionally including an
  issue number such as `fix/425-rss-deleting-bug`.
- Use `feat`, `fix`, `refactor`, `chore`, `docs`, `test`, `build`, `ci`, `release`, or `dependency`;
  avoid vague `dev`, `patch`, or bare-name branches.
- Codex-owned branches retain the desktop prefix, for example
  `codex/feat/semantic-scholar-engine`.
- Keep one independently reviewable concern per branch and align Conventional Commit and PR types.

## Domain and persistence rules

- Keep `PaperWork`, `PaperManifestation`, provider records, and local/generated artifacts separate.
- Normalize DOI, arXiv, PMID, and PMCID identifiers; merge only on exact canonical aliases.
- Similar titles are review candidates, never automatic merge evidence.
- Room is the local source of truth; multi-table writes and identity merges are transactional.
- A shipped schema change increments the database version, exports the schema, and adds a migration test.
- WorkManager executes persisted tasks; it is not a second queue database.

## Network, reader, and extension safety

- Use official provider APIs with explicit rate/concurrency policy and typed offline, 429,
  unavailable, and invalid-response failures. Deterministic tests use local fixtures, not live internet.
- Preserve paper provenance, license, acquisition time, and original-PDF fallback independently.
- Never invent extracted text, silently upload a paper, render unsanitized remote HTML, or compile
  untrusted TeX in the app process.
- Bind annotations to an exact canonical document hash and stable source/text anchor. Never move them
  silently across a manifestation, sanitizer, renderer, parser, or PDF revision.
- Community extensions run as separate packages/UIDs over bounded versioned IPC. Never load
  third-party DEX/JAR/JavaScript into the host or expose host storage/database paths.
- A theme extension is declarative and includes a complete validated semantic icon set. The host owns
  rendering and rejects unknown, oversized, or malformed assets.
- APK installation requires a verified signed-store record, SHA-256/size/package/version/signer/service
  preflight, and Android PackageInstaller user confirmation.

## Development and verification

- Use the Gradle wrapper, JDK 21, Android SDK 36/36.1, and Java/Kotlin bytecode target 17.
- Every non-trivial logic change gets the smallest deterministic test plus the full local gate.
- Preserve coroutine cancellation; do not block the main thread with database, network, parsing,
  hashing, or Binder work.
- Do not add analytics, proprietary SDKs, credentials, private endpoints, destructive migrations,
  lint baselines, or speculative modules.
- Reuse a connected emulator; do not wipe it, factory-reset it, or start a duplicate instance.

```powershell
.\gradlew.bat :extension-api:testDebugUnitTest :extension-api:lintDebug `
  :logic:testDebugUnitTest :logic:lintDebug `
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Run connected tests for Android-runtime or UI changes and report the exact device/API and actions.
A handoff states behavior, public API/schema impact, exact checks, and intentionally deferred work.
