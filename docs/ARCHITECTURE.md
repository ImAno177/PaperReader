# Architecture

Status: enforced by Gradle direction and unit tests.

```text
:app  ───────►  :logic
  │               │
  └───────┬───────┘
          ▼
 :extension-api  ◄──── external source/theme APKs
```

There is no reverse dependency. The logic module has no Compose, Activity, Fragment, ViewModel, View, or Widget imports. The app module must not import Room implementation packages or built-in HTTP providers directly.

## Module ownership

| Module | Owns | Must not own |
| --- | --- | --- |
| `:extension-api` | Published AIDL and bounded source/theme data contracts shared with external APKs | Host storage, networking, UI rendering, trust policy, or provider implementations |
| `:logic` | Paper domain models, DOI/arXiv identity, Room schema and repository, arXiv/Crossref clients, federated search, reader/extraction contracts, cache policy, task state, source-extension trust/runtime | Compose, screens, navigation, Activities, Fragments, ViewModels, visual resources |
| `:app` | Compose UI, adaptive navigation, presentation mapping/filtering, Android lifecycle/entry points, accessibility, theme preferences, theme-extension trust/runtime, and visual resources | SQL/DAO access, HTTP parsing, provider rate policy, dedupe, or extraction |

The UI implementation session should normally edit only `app/**` and the `:app` dependency list. A required logic behavior change belongs in `logic/**` and needs logic tests.

Presentation is organized by feature without adding Gradle modules or pass-through layers:

```text
app/ui/PaperReaderApp.kt          adaptive shell and route wiring
app/ui/<feature state>            application presentation state and mapping
app/ui/screen/<Feature>Screen.kt  one root destination or More branch plus local helpers
app/ui/components/                visual behavior shared by two or more features
app/reader/                       reader Activities and renderer-owned UI
```

Screen interfaces remain the test seam: navigation and Compose tests call the same state-driven root
function. A helper stays feature-private until a second real consumer exists. This replaces the former
multi-feature `HomeScreens.kt` grab bag while preserving every destination interface.

Inside `:logic`, dependencies follow dependency inversion at package level:

```text
UI -> usecase -> domain repository port <- data/repository -> Room query + mapper
              -> provider manager       <- provider/builtin -> network gate/client
              -> task coordinator       <- data/repository -> Room task rows
```

`LogicBoundaryTest` enforces these internal directions. `:extension-api` is separate because two
external sample repositories compile against it and it must be published independently. Another
package becomes a Gradle module only with a second real consumer or proven build isolation need.

## Supported UI entry point

Create one application-scoped instance:

```kotlin
val logic = PaperReaderLogic.open(
    context = applicationContext,
    configuration = PaperReaderConfiguration(
        userAgent = "YourApp/0.1 (Android; https://example.org)",
        contactEmail = "api-contact@example.org",
    ),
)
```

The supported surface is:

- `logic.useCases.observeLibrary.subscribe()` for reactive library state, including aggregate collection membership, local artifacts, reading state, and annotation count without UI-side joins.
- `logic.useCases.savePaper.await(remotePaper)`, `saveSearchResult.await(cluster)`, `getPaper.await(workId)`, `removePaper.await(workId)`, `updateReadingState.await(state)`, and `setReadingStatus.await(workId, status)` for library/reader commands. Removal is transactional and refuses to orphan local artifacts or interrupt active tasks.
- `logic.useCases.searchPapers.subscribe(query)` for isolated multi-provider results and failures.
- `logic.useCases.observeSavedSearches.subscribe()`,
  `createSavedSearch.await(queryText, providerIds)`, `refreshSavedSearch.await(id)`,
  `refreshAllSavedSearches.await()`,
  `deleteSavedSearch.await(id)`, `markSavedSearchHitRead.await(hitId)`, and
  `saveSavedSearchHit.await(hitId)` for the persisted Updates inbox and its bounded background check.
- `logic.useCases.observeReadingHistory.subscribe()`, `recordReadingSession.await(...)`, and `removeReadingHistory.await(...)` for the History screen.
- `logic.useCases.observeCollections.subscribe()`, `createCollection.await(name)`,
  `renameCollection.await(id, name)`, `deleteCollection.await(id)`, and
  `setPaperCollections.await(workId, ids)` for named collections and transactional paper assignment.
- `logic.useCases.observeReadingBookmarks.subscribe(workId, manifestationId, documentSha256)`,
  `toggleReadingBookmark.await(...)`, and `removeReadingBookmark.await(id)` for page bookmarks
  anchored to one exact local PDF.
- `logic.useCases.observeAnnotations.subscribe(workId, documentSha256)`,
  `saveAnnotation.await(workId, selection, note)`, `updateAnnotationNote.await(id, note)`, and
  `removeAnnotation.await(id)` for highlights and optional notes anchored to one exact sanitized
  document hash and stable readable block offsets.
- `logic.useCases.createMetadataBackup.await()`, `previewMetadataRestore.await(bytes)`, and
  `restoreMetadataBackup.await(bytes)` for typed, versioned metadata export, validation/preview,
  and transactional merge restore. The app owns SAF URI access; logic never receives a file path.
- `logic.useCases.prepareLocalPdf.await(contentUri)`, `recoverPendingLocalPdf.await()`,
  `importLocalPdf.await(importToken, title)`, and `discardPendingLocalPdf.await(importToken)` for
  bounded local-PDF ingestion. `prepare` validates and hashes the stream into an app-private,
  process-durable session before UI confirmation; the opaque token never grants filesystem access.
- `logic.useCases.loadReadablePaper.await(workId, manifestationId)` for an exact-version,
  sanitized, app-private readable document. The result is either a provenance-bearing
  `ReadablePaperDocument` or a typed unavailability reason; UI never fetches or sanitizes provider
  HTML itself.
- `logic.providers.state` for installed/available/untrusted provider lifecycle; saved missing providers resolve to explicit stubs.
- `logic.extensionStores.state`, signed-store preview/add/refresh/remove commands, and
  `logic.reconcileSourceExtensions()` for user-confirmed Ed25519 store trust and installed-package
  lifecycle reconciliation. The app receives verified release metadata, never store private keys or
  unverified payloads.
- `logic.tasks.tasks`, `enqueue`, `transition`, `cancel`, `retry`, and terminal removal for persisted download/extraction queue state.
- `logic.downloads.requestDownload(...)`, `cancelDownload(taskId)`, `retryDownload(taskId)`,
  `removeDownloadTask(taskId)`, `execute(taskId)`, `downloadedPaper(...)`, and `deleteDownload(...)`
  for verified local PDF lifecycle. App WorkManager invokes `execute`; Room remains the queue and
  artifact source of truth.
- Domain types under `domain`, provider request/result contracts, and use-case events.

Close the instance when the application-scoped owner is destroyed in tests. Production normally keeps it for the process lifetime.

Room entity classes are public only because the current Room/KSP processor needs code-generation visibility. They are not a supported app API. The architecture test rejects `dev.paperreader.logic.data.*`, built-in provider, and concrete Room repository imports from `:app`.

## Implemented logic

- Exact identity normalization and clustering: DOI URLs/labels, modern and legacy arXiv IDs, arXiv revision separation, provider-scoped IDs, no fuzzy title auto-merge.
- Room v4 schema: the v1 works/authors/identifiers/manifestations/files/reader/history/annotation/task
  baseline, v2 named collections with an indexed many-to-many junction, v3 exact-document page
  bookmarks, and v4 saved searches with per-provider state and bounded hit snapshots. Tested adjacent
  migrations preserve each shipped version; bookmarks and saved-search rows cascade only through
  their declared owners.
- Transactional repository: exact-alias matching, deterministic work IDs, provider enrichment,
  aggregate Flow queries, preservation of older identifiers/manifestations, incoming refresh of the
  same manifestation, normalized case-insensitive collection names, and all-or-nothing membership updates.
- arXiv Atom and Crossref JSON providers: fixture-tested parsing, cursors/sorts, exact arXiv `id_list` and Crossref DOI-filter lookup, request headers, 429 `Retry-After`, single-connection request gates, and provider-specific minimum intervals.
- Shared metadata transport rejects successful response bodies beyond 8 MiB before parsing, including
  chunked responses without `Content-Length`; provider failures remain typed.
- Provider manager: `StateFlow` lifecycle, built-in shadowing protection, runtime registration/removal, available/untrusted catalogs, and missing-provider stubs.
- Federated search: takes a provider-manager snapshot, isolates provider failures, and clusters exact aliases while keeping similar titles separate.
- Saved-search updates: deterministic query/provider identity; Room-backed per-provider
  `lastChecked`/`lastSuccess` and typed rate-limit/unavailable/invalid-response state; bounded,
  versioned `RemotePaper` snapshots; baseline-aware unread detection; exact-alias-only optional Work
  links; monotonic checkpoint rejection of delayed concurrent outcomes; and transactional pruning to
  the newest 200 hits. A refresh queries the newest 20 records from each selected real provider
  concurrently and keeps stale hits when a source fails. The provider set is the exact snapshot
  reported by the federated search event, not a second UI-side registry read. The aggregate interactor
  checks saved searches sequentially so periodic work cannot fan out every query at once.
- Queue/history: deterministic task identity, Room-backed compare-and-set transitions, process-recoverable progress/attempts, CAS-first cancellation, fresh manual-retry budgets, terminal-row cleanup that preserves local PDFs, and accumulated reading-session history projection.
- PDF downloads: cancellable streaming OkHttp transfer, byte limit, `%PDF-` validation, SHA-256
  addressing, app-private atomic files, FileProvider handles, bounded retry, and safe deletion.
- Reflow foundation: versioned extraction cache key, `PdfTextExtractor` boundary for the future pdf-inspector JNI adapter, Markdown/source blocks, source ranges, atomic artifact-store contract, structural/cache-integrity validation before reuse or write, cache reuse, and explicit original-PDF fallback reasons.
- Readable-content source order: validated/sanitized provider full text (official arXiv HTML when
  available) first; future isolated TeX conversion second; PDF extraction to canonical Markdown and
  local HTML render cache third; immutable original PDF fallback last. Remote HTML is never rendered
  directly, and each normalized document carries a version, provenance, and content hash.
- Official arXiv HTML vertical slice: the exact manifestation revision is resolved to a versioned
  `/html/{id}vN` URL, fetched with a bounded cancellable client, and parsed with jsoup. A strict
  safelist retains headings, tables, citations, raster figures, and MathML while removing executable
  markup and unsafe URLs. Figures are fetched only from the same versioned arXiv path under count and
  byte budgets, embedded as data URIs, and replaced by explicit placeholders when unavailable. The
  sanitized artifact and native section index are atomically cached with SHA-256 integrity metadata.
  The disposable cache is capped at 160 MiB and evicts complete least-recently-used document pairs;
  cache publication failure does not prevent the current verified document from opening.
- Community extension SDK: packaged AIDL, bounded source/theme contracts, external APK/UID runtime,
  package/bounded-version/signer/API/kind/descriptor verification, cancellation/timeouts, complete
  semantic theme icons, and real external OpenAlex/Blueprint samples. User-managed Ed25519 stores use
  fingerprint confirmation, strict schemas, monotonic sequences, same-sequence equivocation rejection,
  atomic last-known-good persistence, and explicit HTTPS install/update pages. Release trust is empty by
  default; no official store is preconfigured.
- Metadata backup: a bounded single-entry ZIP with a versioned ProtoBuf payload, strict relational
  and hostile-input validation, exact-identifier restore planning, and one-transaction merge. It
  preserves local files/tasks, reports conflicts, unavailable providers, skipped records, and
  document anchors that remain dormant because the exact local PDF is unavailable. Schema v2 also
  carries saved-search query/provider checkpoints and bounded hit snapshots; restore derives stable
  IDs, remaps exact Work links, and keeps the device's existing read/unread state on collisions.
- Local PDF import: content-only URI validation, bounded streaming, `%PDF-` validation, SHA-256
  identity, durable app-private staging, verified atomic publication, and one Room transaction for
  Work/manifestation/file provenance. Exact duplicates are idempotent only inside the
  `local-pdf` authority; byte equality with a provider download never merges intellectual Works.
  Startup removes known unowned publication remnants left before a process-death DB commit.

## Implemented app vertical slice

- Real launcher and process-scoped logic composition root; Room/OkHttp construction is dispatched
  off the main thread and the first app state is explicit rather than fabricated.
- Five adaptive destinations: Library, Search (the Discover screen), Updates, History, and More, plus
  saved-paper detail. Bottom labels remain single-line at 130% system font scale. Navigation does not
  restore the tab state it just popped when returning to the retained Library start destination.
  More is a hub rather than a long mixed settings list; its secondary branches are Appearance,
  Collections, Reading & imports, Updates & notifications, Data & backup, and Sources/providers.
  Branch routes keep More selected in bottom/rail navigation and provide an explicit back path to the
  hub. Root destination titles use a left-aligned 24sp editorial hierarchy.
- Room-backed library and reading status; local metadata search, status filters, deterministic sort,
  named-collection filters, list/grid presentation, and persistence across process recreation.
- Room-backed collection creation, rename, confirmed deletion, and multi-collection assignment from
  paper detail. Assigned labels appear on detail, collection deletion keeps papers, and no mock or
  presentation-only collection state is used.
- Live federated arXiv/Crossref discovery with incremental results, isolated source failures,
  save/open transitions, exact-identifier behavior, and an idempotent action to track the submitted
  query with the provider snapshot that actually ran. `ACTION_SEND text/plain` accepts only one
  unambiguous DOI or arXiv identifier/URL, preserves an exact arXiv version, opens Discover, and
  submits through that same federated pipeline. Unsupported or ambiguous text is never sent to a
  provider and receives explicit English feedback.
- Updates is no longer a renamed download queue. It observes persisted saved searches and hit
  snapshots, exposes manual refresh/read/save/delete actions, shows per-provider typed failures next
  to stale results, and keeps the download queue as a separate section. Real provider/task/history
  surfaces retain honest loading, empty, offline, and actionable failure copy. Download rows retain
  the paper title and distinguish source refusal/missing files from retryable failures instead of
  exposing or hiding opaque task codes.
- More > Background updates exposes one explicit, disabled-by-default daily saved-search switch.
  The app-owned DataStore preference controls one unique network-constrained periodic WorkManager
  request; Room remains the feed truth and provider gates remain authoritative. Per-provider failures
  are persisted without a retry storm. A notification is posted only for the transactionally reported
  `newlyUnread` count (new provider records or changed fingerprints that were not already unread).
  Android 13 notification permission/channel denial never blocks refresh and is
  shown honestly; notification taps route to Updates. WorkManager timing is described as approximate.
- Room-backed PDF download actions with WorkManager network constraints, live progress, state-aware
  cancel/retry/clear controls, process-death recovery, in-app original-PDF opening, system-viewer
  fallback, and confirmed local-copy deletion. A cancellation that wins the artifact-commit race
  cannot be overwritten by worker completion and rolls back the unpublished app-private file.
- More > Local library imports real PDFs through Android OpenDocument. The launcher also accepts
  `ACTION_VIEW`/`ACTION_SEND` for `content://` PDFs. It creates the private staged copy before the
  title-review dialog, survives Activity/process recreation without relying on a transient URI
  grant, serializes repeated shares through one `singleTask` Activity, and reports exact local
  duplicates without rewriting the first title or provenance. A consumed external intent is
  neutralized before Activity recreation, and each new share pushes the More attention surface
  without restoring a previously saved Detail route over its review dialog.
- Background local-PDF recovery keeps `Preparing` non-navigating; only a state needing user attention
  opens More, so startup is not hijacked while a staged import is reconstructed.
- The original-PDF reader uses the scoped FileProvider URI with AndroidX PDF, supports search,
  zoom/scroll, a visible `Page X of Y` indicator with validated numeric page jump, and configuration changes, writes debounced zero-based page progress through
  `updateReadingState`, restores only on exact manifestation + PDF SHA-256, and records real reader
  sessions through `recordReadingSession`. Configuration changes share one foreground-session
  accumulator, while paused/background time is excluded. Its non-exported Activity validates the
  app-owned content authority. Its toolbar can add/remove the current page bookmark, list bookmarks
  in page order, and jump to a saved page. Bookmark writes survive Activity recreation and are
  accepted only for a readable local artifact with the same Work, manifestation, and canonical
  SHA-256. Viewport callbacks select the page with greatest visible area rather than trusting a
  barely visible `firstVisiblePage`, keeping indicator, bookmark, and progress semantics aligned.
  Original-PDF text highlights remain disabled because AndroidX PDF does not expose a verified text
  selection/source-map contract; page bookmarks remain the only annotation affordance in that mode.
- The mobile reader is the primary action for exact-version arXiv manifestations. It renders only
  the logic-owned sanitized document in a non-exported, source-script-free, network-blocked WebView
  with a deny-by-default CSP. Native TOC navigation temporarily executes only an app-owned
  `getElementById(safeAnchor).scrollIntoView()` command, with a forced disable timeout. The same
  bounded command channel captures a native selection within one sanitizer-assigned block and
  renders Room-backed highlights; JavaScript is disabled again after every callback or timeout.
  Anchors use UTF-16 block offsets, quote prefix/exact/suffix, and the exact sanitized-document
  SHA-256. Notes never enter renderer JavaScript, overlaps are rejected transactionally, and a
  changed document remains stale rather than being silently re-anchored. The reader provides
  a native table of contents, in-document search,
  selectable text, 85–200% reversible text sizing, responsive figures, scrollable math/tables,
  source/version/license disclosure, the persisted System/Light/Dark appearance mode, offline cache reopening, exact
  document-hash progress restoration, reading sessions, and an always-available Original PDF
  fallback when the verified local file exists. Other providers and PDFs without structured HTML
  still fall back honestly; the app does not claim universal reflow yet.
- arXiv work publication dates remain distinct from exact-version manifestation update dates. Atom
  metadata does not prove a content license; the detail screen defers the license label until the
  sanitized official HTML source has supplied it, and the reader reports narrowly normalized source
  conversion artifacts without interpreting arbitrary TeX.
- Three separate visual token packs (Doodle, Retro, and Neobrutalism) sharing one information
  architecture and accessible content typography. Appearance persists System, Light, or Dark
  independently from the visual preset; the resolved mode continues into both reader Activities.
  Status colors meet normal-text contrast on every built-in light canvas/surface. Empty-state accents
  are semantic theme tokens, not a hard-coded purple.
- Library list/grid layout is a persisted presentation preference. Bottom-navigation hit targets
  remain at least 48 dp while all five visible tiles use equal geometry and a separate outer inset;
  visual bounds are not treated as the touch bounds.
- Current app copy and packaged app resources are English-only; the Activity pins English resource
  and plural rules even when the device or per-app locale is different.
- More > Data & backup provides manual SAF export and restore. Restore requires an explicit preview
  confirmation, its bounded app-private pending session survives process recreation, and PDFs,
  downloads, caches, plugins, credentials, and file paths are never placed in the archive. Export
  snapshots the bounded destination before truncation and restores its previous bytes if the SAF
  provider fails or the write is cancelled; the ViewModel never deletes a document URI.

## Intentionally not implemented in this slice

- The audited pdf-inspector commit `a67ee032695388f8b7bbfd029783bd255ebbb8a4` remains outside
  production. A wrapper spike under `build/native-spike/` now exposes a narrow FD+flags JNI entry
  point, emits bounded deterministic PRX1 bytes, embeds CMaps, and has passing arm64-v8a/x86_64
  API-28 cargo-ndk builds with 16 KiB ELF alignment. Only the wrapper library is packaged in the
  spike; the app does not load it. `PdfTextExtractor` remains the tested boundary. Production is
  gated on cancellation, isolated-process execution, bounded input/memory, corpus validation,
  provenance/checksum reporting, target-filtered SBOM/license gates, Adobe CMap/Korea1/AGL attribution,
  and release packaging. The app never substitutes a cloud parser silently.
- OCR engine, extraction worker, automatic backup/sync, resumable range requests, and storage-location
  selection. The AndroidX original-PDF renderer remains an alpha dependency, so an explicit
  system-viewer fallback is retained.
- Global queue pause/resume, user reorder, and bulk actions remain deferred; per-task actions are
  implemented; full queue controls remain deferred.
- Remaining store work includes publisher signing-material rotation, lifecycle states for disabled
  or orphaned packages, review policy, a preconfigured official store, and an in-app APK downloader.
  User-managed Ed25519-signed stores,
  compatible release discovery, system-mediated install/update, and isolated source/theme APKs are
  implemented.
- Tags, smart collections, collection reordering, Original-PDF highlights, annotation export and
  cross-revision re-anchoring, manifestation revision/citation updates, configurable saved-search
  cadence,
  production PDF-to-reflow extraction, isolated TeX conversion, OCR, and structured full text for
  non-arXiv providers. The official arXiv HTML reader and Original-PDF reader are real; unsupported
  content still receives a typed fallback and the app never invents extracted text.

## Verification

Run with Android SDK and JDK configured:

```powershell
.\gradlew.bat :extension-api:testDebugUnitTest :extension-api:lintDebug `
  :logic:testDebugUnitTest :logic:lintDebug `
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`LogicBoundaryTest` fails if UI framework imports enter `:logic`, if `:logic` depends on `:app`, or if future app code bypasses the public boundary.
