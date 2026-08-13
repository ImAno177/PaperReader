# PaperReader contributor instructions

These rules apply to the whole repository. Prefer the smallest complete vertical slice, preserve
existing user work, and never present a stub as a production feature.

## Documentation index

- [`README.md`](README.md) — concise product overview, feature highlights, screenshots, and build entry point.
- [`docs/SPEC.md`](docs/SPEC.md) — product scope, domain decisions, provider research, reader constraints, and roadmap.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module ownership, supported logic facade, implemented behavior, and deferred work.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — pull-request workflow, required checks, and contribution boundaries.
- [`SECURITY.md`](SECURITY.md) — private vulnerability-reporting process and security-sensitive areas.
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — dependency, icon, and content-license attributions.

Read `docs/SPEC.md` and `docs/ARCHITECTURE.md` before changing domain models, module boundaries,
providers, readers, persistence, or extensions. Update the relevant document when a decision changes.

## Project boundary

```text
:app  ─────►  :logic
UI/UX only    domain, use cases, data, providers, reader, tasks, extensions
```

- `:app` owns Compose, Activities, navigation, view-model state, accessibility, English strings,
  themes, icons, and presentation mapping.
- `:logic` owns immutable domain models, repository ports, Room, provider/network policy, reader
  artifacts, task state, backup, and extension trust/runtime boundaries.
- UI code consumes one application-scoped `PaperReaderLogic` facade. It must not import Room DAOs,
  concrete repositories, OkHttp clients, provider parsers, extraction internals, or plugin binders.
- `:logic` must not import Compose, Android UI classes, navigation, or visual resources.
- Never weaken or baseline `LogicBoundaryTest` to make a build pass.

## Domain and persistence rules

- Keep `PaperWork`, `PaperManifestation`, provider records, and local/generated artifacts separate.
- Normalize DOI and arXiv identifiers; merge automatically only on exact canonical aliases.
- Similar titles are review candidates, never automatic merge evidence.
- Room is the local source of truth; multi-table writes and identity merges are transactional.
- A shipped schema change requires an incremented database version, exported schema, and migration test.
- WorkManager executes persisted tasks; it is not a second queue database.

## Network, reader, and extension safety

- Use official provider APIs with explicit rate/concurrency policy and typed offline, 429, unavailable,
  and invalid-response failures; deterministic tests use local fixtures, not live internet.
- Preserve paper provenance, license, acquisition time, and original PDF fallback independently.
- Never invent extracted text, silently upload a paper, render unsanitized remote HTML, or compile
  untrusted TeX in the app process.
- Community extensions run as separate Android packages/UIDs over a versioned bounded IPC contract.
  Never load third-party DEX/JAR/JavaScript into the host process or expose host storage/database paths.
- A theme extension is declarative data and includes a complete validated semantic icon set; the host
  owns rendering and rejects unknown, oversized, or malformed assets.

## Development and verification

- Use the Gradle wrapper, JDK 21, Android SDK 36/36.1, and Java/Kotlin bytecode target 17.
- Every non-trivial logic change gets the smallest deterministic test plus the full local gate.
- Preserve coroutine cancellation; do not block the main thread with database, network, parsing,
  hashing, or Binder work.
- Do not add analytics, proprietary SDKs, credentials, private endpoints, destructive migrations,
  lint baselines, or speculative modules.
- Reuse a connected emulator; do not wipe it, factory-reset it, or start a duplicate instance.

```powershell
.\gradlew.bat :logic:testDebugUnitTest :logic:lintDebug `
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Run connected tests for Android-runtime or UI changes and report the exact device/API and actions.
A handoff must state behavior, public API/schema impact, exact checks run, and intentionally deferred work.
