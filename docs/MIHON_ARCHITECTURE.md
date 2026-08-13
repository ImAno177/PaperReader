# Mihon Architecture Study → Paper Reader Structure

Reference snapshot: [`mihonapp/mihon@2506b049`](https://github.com/mihonapp/mihon/tree/2506b049642af2211c1ef81e7369f752363f655d), committed 2026-08-09. The analysis is pinned so later Mihon changes cannot silently change our conclusions.

## 1. What Mihon actually separates

Mihon is not simply “one app module with screens.” Its root graph contains `app`, `domain`, `data`, `source-api`, `source-local`, multiple `core` modules, presentation modules, metadata, telemetry, and benchmark modules. The important dependency direction is:

```text
source-api ─────► core-common
     │
     ├──────────► domain ◄──────── data
     │                ▲              ▲
     └────────────────┴──────────────┘

app ─► domain + data + source-* + core-* + presentation-*
```

Evidence:

- [Module declarations](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/settings.gradle.kts#L31-L45).
- [`domain` dependencies](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/domain/build.gradle.kts) include `source-api` and `core.common`.
- [`data` dependencies](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/data/build.gradle.kts) include `domain`, `source-api`, and `core.common`.
- [`app` aggregation](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/build.gradle.kts#L206-L220) consumes the domain, data, source, core, and presentation modules.

The architectural value is the direction, not the number of Gradle modules. Mihon has years of scale; copying every module into a new project would create empty abstractions.

## 2. Domain → repository → data adapter

Mihon UI/view-model code calls small domain interactors. Interactors depend on repository interfaces, while the data module implements those interfaces using generated SQL queries and explicit mappers.

Representative chain:

1. Immutable domain aggregate: [`Manga`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt).
2. Repository port: [`MangaRepository`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/domain/src/main/java/tachiyomi/domain/manga/repository/MangaRepository.kt).
3. Small interactor: [`GetLibraryManga`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/domain/src/main/java/tachiyomi/domain/manga/interactor/GetLibraryManga.kt).
4. Data adapter: [`MangaRepositoryImpl`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt).
5. Row mapper: [`MangaMapper`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/data/src/main/java/tachiyomi/data/manga/MangaMapper.kt).
6. Schema/upsert semantics: [`mangas.sq`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/data/src/main/sqldelight/tachiyomi/data/mangas.sq).
7. Library projection: [`libraryView.sq`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/data/src/main/sqldelight/tachiyomi/data/view/libraryView.sq).

Paper mapping:

| Mihon | Paper Reader |
|---|---|
| `Manga` | `PaperWork` |
| source URL + source ID | canonical aliases + provider record ID |
| `Chapter` | manifestation plus local/original/reflow artifacts |
| library view | library aggregate with progress, artifacts, and annotations |
| history row/view | reading session and latest-reading projection |
| `MangaRepository` | `LibraryRepository` |
| `GetLibraryManga` | `ObserveLibrary` |
| `insertNetworkManga` | exact-identity, idempotent provider-record merge |

Adopt now:

- Repository interfaces live with domain contracts.
- Concrete Room repositories stay in `data` and remain hidden from UI.
- Room aggregate queries replace UI-side joins.
- Mappers are explicit and testable.
- UI calls interactors rather than DAOs or HTTP providers.

Intentional difference: Mihon sometimes catches an exception and returns a Boolean. Paper/network/extraction failures need typed states (`rate-limited`, `offline`, `invalid response`, `needs OCR`) so UI can explain the actual condition.

## 3. Source registry and community extensions

Mihon’s [`Source`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/source-api/src/main/kotlin/eu/kanade/tachiyomi/source/Source.kt) contract has a stable unique ID and suspend detail/page operations. [`AndroidSourceManager`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt) combines built-in/local/extension sources into a `StateFlow`, persists source stubs, and supports `getOrStub` when an extension disappears.

[`ExtensionLoader`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt) validates extension library versions and signatures, then loads third-party classes using a child-first class loader. [`ExtensionManager`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt) publishes installed/available/untrusted lifecycle state.

Adopt:

- `ProviderManager` with one state snapshot for installed, available, and untrusted providers.
- Stable provider IDs and `getOrStub` so saved papers remain readable when a plugin is absent.
- Signed index, version compatibility, certificate fingerprint, package lifecycle, bounded capabilities.
- Suspend-only provider API; no Rx compatibility layer.

Security deviation — mandatory:

- Do **not** copy Mihon’s in-process `DexClassLoader` model.
- Community paper providers run as Android services in their own package/UID.
- Host binding must be explicit, signature/certificate checked, protected by a signature-level permission, bounded by payload size and timeout, and cancellable.
- Host never gives a plugin its Room database, storage root, arbitrary intent, or global token.

The current slice implements provider registry state, stubs, plugin compatibility/trust, and built-in providers. AIDL binding is deferred until an isolated-process demo exists; this is stated rather than hidden behind a fake plugin implementation.

## 4. Queues, workers, and process death

Mihon separates queue coordination from Android scheduling:

- [`DownloadManager`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt) owns one coordinator and exposes queue state.
- [`DownloadStore`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadStore.kt) restores ordering after process death.
- [`DownloadJob`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadJob.kt) applies WorkManager uniqueness and network constraints.
- [`LibraryUpdateJob`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt) adds backoff, scheduling, and bounded per-source concurrency.

Paper adaptation:

- Room task rows are the source of truth for download and extraction state.
- `TaskCoordinator` owns valid state transitions and deduplication.
- The app `DownloadWorker` executes a task under a unique, network-constrained WorkManager chain;
  it does not own a parallel queue/database. Room recovery was verified across force-stop and
  offline-to-online transitions.
- Following Mihon's state-specific download actions, active Paper Reader rows expose cancel,
  failed/cancelled rows expose retry and clear, and completed rows expose clear without deleting the
  verified PDF. Room wins cancellation by compare-and-set before WorkManager is cancelled; manual
  retry cancels the stale unique chain before resetting the persisted attempt budget.
- Worker completion cannot overwrite a cancellation. If cancellation wins after bytes were moved
  but before the task commit, the unexposed artifact row/file is rolled back deterministically.
- Download and extraction are separate task kinds so a PDF can exist while reflow extraction fails.
- Provider-specific request gates enforce rate and connection limits independently of WorkManager.
- Mihon's global pause/resume, reorder, and bulk queue controls are still deferred and remain a
  named parity gap rather than being represented by inactive UI.

### Updates are a persisted feed, not a worker-shaped screen

At the pinned revision, Mihon's
[`LibraryUpdateJob`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt)
discovers and persists chapter changes, while
[`updatesView.sq`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/data/src/main/sqldelight/tachiyomi/view/updatesView.sq),
[`UpdatesRepository`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/domain/src/main/java/tachiyomi/domain/updates/repository/UpdatesRepository.kt),
and [`GetUpdates`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/domain/src/main/java/tachiyomi/domain/updates/interactor/GetUpdates.kt)
form a bounded reactive feed for presentation. The useful pattern is the separation between fetching,
durable update records, and UI observation; manga chapter semantics are not transferable.

Paper Reader adapts that pattern in Room v4 with `saved_searches`, `saved_search_sources`, and
`saved_search_hits`. Creating the same normalized query/provider set is idempotent. Refresh
uses the existing live provider contracts and request gates, stores independent typed status per
source, keeps stale hits on partial/offline failure, and prunes each feed to the newest 200 bounded
versioned snapshots. The first successful response establishes a read baseline; later provider IDs
or metadata fingerprints become unread. Exact canonical aliases may link a hit to a library Work,
but equal titles never do. The provider identity comes from the federated search's own snapshot.
Monotonic attempt checkpoints plus transactional stale-outcome rejection prevent delayed concurrent
responses from regressing metadata, failure state, or timestamps. Updates renders this inbox
separately from the download queue.

The worker/feed split is now adapted without copying manga update semantics. An app-owned DataStore
switch, disabled by default, reconciles one unique network-constrained periodic WorkManager request.
The worker calls a facade interactor that visits saved searches sequentially, so query count cannot
multiply provider fan-out; Room and the existing provider gates remain authoritative. Typed provider
failures complete the periodic run and stay visible in the feed. Only the transactional `newlyUnread`
aggregate can trigger the saved-search notification, and denied Android notification permission or
channel state is shown without disabling background refresh. Revision/citation detection, configurable
cadence, and cursor/ETag deltas remain deferred. The current arXiv/Crossref contract exposes a newest
page rather than a proven delta protocol, so cursor state would still be speculative.

## 5. Reader and history

Mihon’s [`ChapterLoader`](https://github.com/mihonapp/mihon/blob/2506b049642af2211c1ef81e7369f752363f655d/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt) chooses a local download, local source, or HTTP loader and returns explicit loading/error state. Reader progress is written through domain history/update interactors rather than screen-owned storage.

Paper adaptation:

```text
provider structured full text (validated/sanitized, e.g. official arXiv HTML)
    → versioned local document model/render cache with provenance + content hash
        ↘ unavailable → local PDF extraction through PdfTextExtractor
            → readable Markdown + source map + safe local HTML cache
                ↘ empty/unsupported → original PDF fallback with reason
```

- Provider HTML is never rendered raw. It is fetched under bounded network/input policy, validated,
  sanitized, normalized, and cached locally before display. A future TeX input is isolated and
  resource-limited; it is not compiled in the host process because active macros/Turing-complete
  behavior and archive traversal/decompression bombs make an in-process converter unsafe.
- For PDF-derived artifacts, Markdown/source blocks are canonical reflow content and HTML is a render
  cache. Provider full text is normalized into the same versioned document model rather than trusted
  as raw markup. `ExtractionService`
  validates cache identity, Markdown checksum, page count, block IDs/content, source-range bounds,
  and fresh parser output before cache reuse/publication; invalid artifacts produce the original-PDF
  fallback reason rather than being rendered.
- Reader position and annotation anchors remain tied to PDF hash plus extraction version.
- A loader returns `Reflow`, `OriginalPdfFallback`, or typed `Failure`; UI never guesses.
- Reading history is a repository/use-case concern, separate from visual reader state.
- The current original-PDF slice follows that direction: a non-exported app Activity consumes a
  scoped `DownloadedPaper` handle, AndroidX PDF renders the immutable file, viewport callbacks are
  persisted through the public reading-state interactor, and history is recorded through the
  history interactor. Position restoration requires the same manifestation and exact SHA-256. The
  viewport chooses the page with greatest visible area, not a sliver-only first-visible page, so
  indicator, bookmark, and progress agree; exact-document restore is unchanged.
  A retained foreground-session accumulator joins configuration changes into one session and never
  counts paused/background time; final position and history writes remain serialized.
- The first reflow strategy is now implemented for exact-version official arXiv HTML. Logic owns the
  bounded fetch, semantic jsoup safelist, same-version raster figure budgets, provenance/hash, native
  section index, and atomic cache. App owns a non-exported local-only WebView renderer with JavaScript
  and network disabled, native TOC/search/text zoom, theme mapping, exact-document progress, and the
  Original fallback. This adapts Mihon's loader lifecycle without treating remote HTML as executable
  source content or pretending PDF extraction is complete.
- Search, zoom, scroll, a visible `Page X of Y` indicator with validated numeric page jump, dark/light chrome, rotation, process recreation, and external-viewer fallback
  are implemented. Doodle, Retro, and Neobrutalism palettes cross the Compose/View boundary rather
  than resetting in the reader. AndroidX annotation/edit controls are intentionally hidden because
  Paper Reader has not yet implemented hash-anchored sidecar annotations or safe export.
- Mihon's reader bookmark path persists an explicit reader action through its domain update layer.
  Paper Reader adapts the interaction without copying manga/chapter state: Room v3 stores a
  zero-based page against `workId + manifestationId + documentSha256`, the original-PDF toolbar
  toggles the current page and lists/jumps bookmarks, and the repository rejects stale, non-local,
  or wrong-manifestation artifacts. Highlight and note anchors remain deferred rather than being
  represented by a page-only bookmark.
- The AndroidX PDF host also guards `Application.onCreate()` so non-main/isolated processes do not
  initialize app-owned Room, network, notification, or scheduler state.
- The pinned pdf-inspector work remains a non-production spike under `build/native-spike/`.
  Its narrow FD+flags JNI wrapper emits bounded deterministic PRX1 bytes, embeds CMaps, and produces
  arm64-v8a/x86_64 API-28 cargo-ndk builds with 16 KiB alignment. Only the wrapper library is
  packaged in the spike; it is not loaded by the app. Cancellation, isolated-process execution,
  bounded input/memory, corpus coverage, provenance/checksum reporting, target-filtered SBOM/license
  gates, Adobe CMap/Korea1/AGL attribution, and release packaging remain mandatory gates.

## 6. UI information architecture adaptation

Paper Reader keeps five top-level destinations (Library, Discover, Updates, History, More), then
uses More as a progressive-disclosure hub instead of a mixed settings dump. Its six secondary
branches are Appearance; Collections; Reading & imports; Updates & notifications; Data & backup; and
Sources/providers. This preserves Mihon's discoverability while keeping each screen task-focused.
Root destination titles are left-aligned with a 24sp editorial hierarchy; branch routes retain More
as the selected adaptive navigation destination and expose an explicit back path to the hub.

## 7. Backup and preferences

Mihon uses a versioned ProtoBuf backup envelope and validator before restore, and typed preference
services over a generic preference store. Paper Reader now adapts the backup pattern without copying
manga-specific records or Mihon's broad preference catalog.

Implemented adaptation: manual SAF export writes one bounded ZIP entry containing a versioned
ProtoBuf metadata payload. Logic validates the complete relational graph before any restore write,
previews new/merged/skipped Works, conflicts, unavailable providers, and dormant exact-document
anchors, then applies the same deterministic plan in one Room transaction. Exact aliases are the
only automatic merge evidence. Local PDFs, download/task rows, caches, plugin APKs, credentials,
and private paths are excluded and existing local artifacts are never replaced. The app persists
only the bounded pending restore archive in `noBackupFilesDir` so preview survives process death;
SAF access remains in `:app`, which snapshots and recovers a bounded destination instead of deleting
it after a failed write. Automatic scheduling, attachment bundles, cloud/folder sync, and the
  broad settings catalog and saved-search rows remain deferred. The existing visual theme preference stays a small typed
DataStore use case rather than justifying a generic settings layer.

Mihon's local-source idea is adapted as an Android document/share ingress, not copied as a
manga-folder model. `ACTION_VIEW`, `ACTION_SEND`, and OpenDocument accept only `content://` PDFs.
Logic performs a bounded one-pass validation/hash/copy into an app-private durable session before
showing confirmation, so process recreation and transient provider grants cannot invalidate the
review dialog. Confirmation publishes a verified content-addressed copy and commits deterministic
`local-pdf` provenance transactionally. Hash equality deduplicates only that local authority; it
does not silently attach an imported document to an unrelated provider Work. Folder watching,
BibTeX/RIS/CSL ingestion, and user-selected storage roots remain separate deferred capabilities.

## 8. Target structure in this repository

The requested hard split remains two Gradle modules:

```text
app/                         # UI/UX session only
logic/
  domain/                    # immutable models and state machines
  repository/                # repository ports
  usecase/                   # small UI-facing interactors
  data/                      # Room rows, aggregate queries, mappers
  data/repository/           # concrete Room adapters
  provider/                  # stable provider contracts and manager state
  provider/builtin/          # arXiv/Crossref adapters only
  network/                   # shared HTTP and per-provider quota gates
  reader/                    # loader/extraction/cache/source-map contracts
  task/                      # persisted task contract and coordinator
  plugin/                    # trust/version policy; IPC runtime later
```

Why not create all Mihon modules now:

- There is only one UI consumer and one persistence implementation.
- Empty `core`, `presentation-widget`, telemetry, metadata, and local-source modules would add Gradle/API overhead without isolation value.
- Package-boundary tests give immediate protection. A package becomes a Gradle module when it has a second consumer, needs an external artifact (`provider-sdk`), or materially improves build/native isolation (`reader`).

## 9. Keep / Adapt / Defer ledger

| Mihon pattern | Decision | Paper implementation |
|---|---|---|
| Domain repository ports + tiny interactors | Keep | UI consumes use cases/Flow |
| SQLDelight generated DB and projections | Adapt | Keep Room now; use aggregate queries/mappers and exported schema |
| StateFlow source/extension manager + stubs | Keep | `ProviderManager` |
| Per-host rate-limit interceptor | Keep | monotonic provider request gates |
| Persisted download queue + WorkManager | Adapt | Room task state + later workers |
| Persisted Updates projection separate from update worker | Adapt | Room v4 saved-search/source/hit feed with manual live refresh, baseline/unread state, stale-result retention, a separate download section, and one opt-in daily WorkManager executor that notifies only transactional new-unread results; defer revision/citation and cursor deltas |
| Categories + manga/category junction + category-filtered library | Adapt | Room v2 named collections, indexed work/collection junction, domain Flow/use cases, transactional assignment, and one Library filter row; defer flags, reorder, a persisted default row, pager tabs, and bulk-selection parity |
| Local source + share ingress | Adapt | bounded SAF/`content://` PDF prepare-review-commit flow, process-durable private staging, exact `local-pdf` SHA identity, and serialized external intents; defer watched folders and citation-file import |
| Reader loader strategies | Adapt | exact-version official arXiv HTML strategy with bounded sanitize/cache + local-only mobile renderer; AndroidX original-PDF fallback; defer PDF extraction/TeX/OCR |
| Reader navigation/bookmark actions + persisted domain update | Adapt | Original-PDF `Page X of Y` indicator with validated page jump, plus Room v3 exact-PDF page bookmarks, toolbar toggle, ordered list/jump, and process-death persistence; highlight/note sidecars remain separate |
| Versioned backup + validator | Adapt | manual metadata-only ProtoBuf/ZIP export, strict preview/validation, process-safe pending session, and transactional exact-alias merge; automatic scheduling and attachment bundles remain deferred |
| Typed preference store | Adapt | one app-owned Preferences DataStore stores the visual preset and disabled-by-default automatic saved-search refresh switch; Room remains domain truth |
| Injekt service locator | Do not copy | explicit constructor composition |
| Rx compatibility | Do not copy | suspend/Flow only |
| In-process extension class loading | Do not copy | separate-UID service IPC |
| Presentation/core/widget modules | Defer to UI session | app owns presentation |
| Telemetry/baseline/profile/flavors | Defer | no current product need |

This mapping is the authority for the next refactor; `SPEC.md` remains the product/feature authority.
