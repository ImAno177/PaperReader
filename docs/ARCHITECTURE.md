# Architecture

Status: enforced by Gradle dependencies and `LogicBoundaryTest`. This file describes the current
host boundary; deferred work is listed at the end.

```text
PaperReader app repository                 PaperReader-sources repository

:app ---> :logic ---> :extension-api <--- external source APKs
                    ^                 <--- external theme APKs
                    |
              bounded AIDL IPC
```

The host contains no provider implementation. Official and community providers are separate Android
packages, run under separate UIDs, and communicate through the versioned `:extension-api` contract.
The official provider repository is
[`ImAno177/PaperReader-sources`](https://github.com/ImAno177/PaperReader-sources).

## Module ownership

| Module | Owns | Must not own |
| --- | --- | --- |
| `:extension-api` | Published AIDL, bounded source/theme descriptors, records, requests, and result codes | Host storage, networking, UI, trust policy, or provider implementations |
| `:logic` | Domain models, exact identity, Room, repositories, provider orchestration, signed extension-store verification, reader artifacts, cache policy, and persisted tasks | Compose, Activities, navigation, visual resources, or concrete provider parsers |
| `:app` | Compose UI, adaptive navigation, presentation state, Android lifecycle, accessibility, WorkManager entry points, installer UI, themes, and icons | DAO access, provider HTTP/parsing, identity merging, or extraction internals |
| External source APK | One upstream API, its request/rate policy, parser, fixtures, and provider tests | Host database, host files, host UI, another provider, or in-process code loading |
| External theme APK | Declarative semantic palette, typography, shape tokens, and a complete icon set | Executable UI code or access to host data |

Dependency direction never reverses. `:logic` has no Compose, Activity, Fragment, ViewModel, View, or
Widget imports. `:app` talks to one application-scoped `PaperReaderLogic` facade and may not import
Room, network, parser, or Binder implementations.

## Current host capabilities

| Capability | Host owns | External package owns |
| --- | --- | --- |
| Search and metadata | Exact identity, provider routing, clustering, ranking, failure state, and persistence | Upstream API requests, parsers, rate policy, and provider records |
| Mobile reading | Sanitization, cache integrity, WebView isolation, layout controls, annotations, and export | Full-text or manifestation data supplied through the extension contract |
| Extensions | Signed-store verification, package preflight, installation consent, lifecycle state, and reconciliation | One source or theme APK, its descriptor, tests, and release artifacts |
| UI and storage | Compose screens, navigation, Room repositories, local files, tasks, backups, and preferences | No host database, filesystem, or executable code access |

## Code organization

```text
app/ui/PaperReaderApp.kt           adaptive shell and route wiring
app/ui/screen/<Feature>Screen.kt   one root destination or More branch
app/ui/components/                 behavior shared by at least two features
app/reader/                        renderer Activities and security boundary
logic/domain/                      immutable domain models and identity
logic/usecase/                     application behavior
logic/data/                        Room and repository implementations
logic/plugin/                      extension trust, discovery, and bounded IPC
```

Root screen functions are the UI test seam. A helper remains feature-private until a second real
consumer needs the same behavior. Production Kotlin files should stay below 600 lines; split at a
feature, state-machine, or lifecycle seam instead of introducing pass-through wrappers.

## Domain and persistence

- `PaperWork`, `PaperManifestation`, provider records, and local/generated artifacts are distinct.
- DOI and arXiv identifiers are canonicalized. PMID and PMCID are normalized independently.
- Automatic merging requires an exact canonical alias. Similar titles are review candidates only.
- Citation counts are timestamped provider observations, not canonical Work metadata.
- Room is the local source of truth. Multi-table mutations and identity merges are transactional.
- WorkManager executes persisted tasks; it is not a second queue database.
- An annotation belongs to one exact sanitized document hash and stable source/text anchor. It is
  never silently moved across revisions, renderers, sanitizers, or files.

## Provider roles

Provider capabilities are explicit and independently selectable:

| Provider | Role | Default |
| --- | --- | --- |
| Semantic Scholar | Free-text search and citation observations | Search engine |
| Crossref | Exact DOI metadata enrichment | Metadata engine |
| arXiv | arXiv search and content manifestations | Content source |
| Europe PMC | Biomedical search and licensed content manifestations | Content source |

Crossref is never used for fuzzy discovery. Exact DOI/arXiv/PMID/PMCID requests route only to an
extension that declares that identifier type. A provider failure remains isolated and cannot cancel
successful providers. Ranking is deterministic: exact identifier, title/text match, then a Semantic
Scholar citation tie-break, publication date, and stable record key.

The Google fallback is browser-mediated, not a provider implementation: the host opens a constrained
`site:arxiv.org` query, accepts only an explicit arXiv `/abs/`, `/html/`, or `/pdf/` VIEW/share
handoff, normalizes the identifier, and lets the installed arXiv extension call its API. The host
never scrapes Google result pages or stores Google credentials.

Installed providers are enabled by default. A user may disable an engine without uninstalling it;
the disabled provider remains available for saved-record provenance and direct access but is excluded
from new federated discovery and identifier-resolution calls. This selection is persisted by the app.

## Extension trust and updates

PaperReader uses the same observable lifecycle states as Mihon and keeps APK verification at the host
trust boundary:

```text
refresh signed store
  -> compare package + versionCode + API compatibility
  -> expose available/update/orphaned/untrusted state
  -> user requests install
  -> bounded download queue
  -> verify size + SHA-256 + package + version + signer + service descriptor
  -> Android PackageInstaller confirmation
  -> package broadcast
  -> rescan installed packages and reconcile state
```

The host pins the official store URL, store ID, and Ed25519 public key. Store documents are verified
before parsing releases and are persisted atomically as last-known-good data. Sequence numbers cannot
move backward or equivocate. User stores require explicit fingerprint confirmation.

The host never loads third-party DEX, JAR, or JavaScript. Each source is a separate Android package
and UID over bounded AIDL. Before installation, the host verifies the signed release's APK SHA-256,
declared byte size, package name, version, signer certificate, service action, contract version, and
extension kind. Android owns the final user-consent surface. Package add/replace/remove broadcasts
trigger a fresh scan; missing store entries are shown as orphaned, not silently removed.

The first release intentionally excludes silent/background APK installation, Shizuku, root, and
private class loaders. Update checks may run at cold start, on manual refresh, and through constrained
periodic work; installing or upgrading always requires the platform-mediated user action.

## Reader boundary

Readable content uses this order:

1. Verified provider full text, with official arXiv HTML preferred for an exact manifestation.
2. A future isolated TeX conversion service.
3. Local PDF extraction into a versioned, provenance-bearing artifact.
4. Immutable original-PDF fallback.

Remote HTML is fetched with byte/time/host limits, sanitized in logic, stored app-private with a
content hash, and rendered in a non-exported, network-blocked WebView with a deny-by-default CSP.
The UI never renders an unsanitized provider response. Figures, math, tables, citations, source,
version, and license remain explicit. Bibliography jumps provide a native return action; find and
table-of-contents controls remain reachable without scrolling to the top.

## Supported host surface

The app creates one process-scoped instance:

```kotlin
val logic = PaperReaderLogic.open(
    context = applicationContext,
    configuration = PaperReaderConfiguration(
        userAgent = "PaperReader/<version> (Android; <project-url>)",
        contactEmail = "<api-contact>",
    ),
    builtInProviders = emptyList(),
)
```

Supported access is through `logic.useCases`, `logic.providers`, `logic.extensionStores`,
`logic.tasks`, and `logic.downloads`. UI code receives immutable state and invokes commands through
these facades. Room entities are public only where code generation requires visibility and are not an
app API.

## Verification

Host gate:

```powershell
.\gradlew.bat :extension-api:testDebugUnitTest :extension-api:lintDebug `
  :logic:testDebugUnitTest :logic:lintDebug `
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

When a production-signed host is already installed on the shared emulator, connected UI tests use
`-PpaperReaderConnectedTestApplicationIdSuffix=.uitest`. This installs an isolated debug test host
without replacing the release app or deleting its data; it is not set for normal debug/release builds.

Provider parser, fixture, lint, signed-APK, registry, and SBOM checks run in the external provider
repository. Android-runtime and UI changes also run connected tests on a declared emulator.

`LogicBoundaryTest` rejects provider modules in the host repository, reverse dependencies, UI imports
inside logic, and app code that bypasses the public facade.

## Deferred work, not shipped

- Production-grade isolated TeX and PDF reflow extraction beyond the verified arXiv HTML path.
- OCR, annotation export, and cross-revision annotation re-anchoring.
- Publisher signing-key rotation and a public extension review/moderation process.
- Automatic backup/sync, resumable downloads, and user-selectable storage locations.

Deferred behavior must remain explicit in UI and typed failures; the app never invents paper text,
licenses, successful installs, or provider data.
