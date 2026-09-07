# PaperReader architecture, plugin, theme, and UX design

Status: Approved for implementation on 2026-09-07. This design extends the approved readable-assets
pipeline in [`2026-09-06-mihon-readable-assets-design.md`](2026-09-06-mihon-readable-assets-design.md).

## Purpose

This document defines the implementation boundary for the next PaperReader release. It combines a
full screen-level MVVM/UDF refactor, an arXiv readable-document plugin boundary, Mihon-inspired
Material 3 theme tokens, and the requested Library, Paper Detail, More, and reader UX changes. It
also defines the local-only functional and performance verification gate, the branch/review order,
and the documentation handoff.

The product remains a local-first scholarly paper library. Existing working behavior is preserved
unless this document explicitly changes its presentation or the arXiv ownership boundary.

## Evidence and references

The current host was audited before this design was written. `PaperReaderApp.kt` is a large
composition root with a broad callback matrix, `PaperReaderViewModel` owns unrelated screen state,
and the host still contains arXiv-specific readable loaders and fetchers alongside the plugin-backed
path. The current UI already has a download queue with live progress and a back action, separate body
and asset caches, a Neobrutalism token system, and Library author truncation; this work simplifies
and consolidates those paths rather than replacing working behavior speculatively.

The architectural direction follows Android's guidance that a screen-level state holder exposes
immutable UI state, receives events from the UI, and keeps data flow unidirectional:

- [Android UI layer and UDF](https://developer.android.com/topic/architecture/ui-layer)
- [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- [Compose UI architecture](https://developer.android.com/develop/ui/compose/architecture)
- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)

The Mihon comparison is limited to observable design and state-ownership patterns. Its current
More surface is a small state holder that provides download status and preference values to a
presentation-only screen:

- [Mihon MoreTab.kt](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt)
- [Mihon MoreScreen.kt](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt)
- [Mihon TachiyomiTheme.kt](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt)
- [Mihon default color scheme](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/presentation/theme/colorscheme/TachiyomiColorScheme.kt)

PaperReader will not copy Mihon's navigation framework, icons, source code, or screen layout
verbatim.

## Goals

1. Make every root destination and More branch own a focused screen-level `ViewModel` and immutable
   `UiState`, while keeping navigation and Android result handling in the UI layer.
2. Remove the callback matrix and unrelated state collection from `PaperReaderApp`.
3. Make arXiv HTML fetch, provider-specific parsing, and provider-specific sanitization belong to
   the arXiv source extension. The host consumes a neutral, verified readable-document contract.
4. Keep HTML/document acquisition and figure-asset acquisition in separate bounded lanes. Remove
   the arbitrary figure-count limit while retaining byte, SVG-complexity, concurrency, retry, and
   cache-quota protections.
5. Add a selectable Mihon-inspired Material 3 preset without changing Neobrutalism as the default.
6. Simplify More, Library, Paper Detail, and reader chrome while preserving accessibility, source
   provenance, original-PDF fallback, annotations, find, contents, citation return, and back.
7. Verify functional behavior and performance locally on the declared emulator before any GitHub
   push or merge. Do not add a new GitHub test runner or rely on GitHub for functional/benchmark
   evidence.

## Non-goals

- No new provider, account, sync, analytics, advertising, cloud parser, or upload behavior.
- No third-party executable code, DEX, JAR, JavaScript, or Compose code loaded into the host.
- No removal of per-asset byte limits, SVG validation, cache quotas, cancellation, or provenance.
- No fuzzy identity merges, destructive Room migration, or speculative module extraction.
- No redesign of the PDF renderer beyond preserving the existing original-document path.
- No pixel-by-pixel test suite. UI polish is verified by the local adb audit and visual review.
- No GitHub-hosted functional or benchmark test addition. Existing security workflows remain the
  security gate.

## Architectural shape

The module boundary remains:

```text
:app ---> :logic ---> :extension-api <--- source/theme APKs
```

The following ownership rules are unchanged:

- `:app` owns Compose, screen-level ViewModels, navigation, Activities, accessibility, preferences,
  themes, icons, and Android result/lifecycle handling.
- `:logic` owns immutable domain models, repositories, use cases, Room, task persistence, reader
  cache policy, extension trust, and generic readable-document/asset verification.
- `:extension-api` owns only the versioned neutral AIDL/data contract.
- Source extensions own upstream HTTP, rate policy, parsing, provider-specific normalization, and
  readable-document sanitization.
- Theme extensions remain declarative and are validated and rendered by the host.

The refactor changes state ownership inside `:app`; it does not reverse module dependencies.

### Composition root

`PaperReaderApp` becomes a thin composition root. It may:

- resolve the selected built-in or community theme;
- collect the small set of global preference values needed to render the shell;
- install lifecycle observers for extension reconciliation and notification availability;
- handle incoming PDF/reference intents and Android document/permission launchers;
- construct the screen ViewModel factory/dependency bundle;
- pass navigation lambdas and global effect handlers into the route layer.

It must not collect the complete Library, Search, History, More, Detail, task, backup, provider,
or extension-store state set and then pass all callbacks to every destination.

### Screen-level MVVM/UDF

Each destination gets a focused state holder. A screen ViewModel exposes one primary `StateFlow`
whose value contains all data needed by that screen, plus narrowly scoped one-shot effects where a
navigation or Android result cannot be represented as persistent state. UI events flow upward into
the ViewModel; state flows downward into the Composable.

The preferred shape is:

```kotlin
data class LibraryUiState(...)

sealed interface LibraryAction {
    data class SelectStatus(val filter: LibraryStatusFilter) : LibraryAction
    data class OpenPaper(val workId: String) : LibraryAction
    data object Discover : LibraryAction
}

class LibraryViewModel(...) : ViewModel() {
    val uiState: StateFlow<LibraryUiState> = ...
    fun onAction(action: LibraryAction) { ... }
}
```

The exact class names may follow existing project naming if that keeps the diff smaller. Do not add
a generic `BaseViewModel`, generic event bus, service locator, or one-implementation interface only
to make screens look uniform.

### Destination ownership

The following is the intended ownership map. Existing presentation models and controllers should be
reused where they already express the correct behavior.

| Surface | State holder | Primary state |
| --- | --- | --- |
| Library | `LibraryViewModel` | papers, collections, layout preference, filter/sort state |
| Search/Discover | `DiscoverViewModel` | query, provider progress/errors, results, saved-search actions |
| History | `HistoryViewModel` | reading history and removal state |
| Updates | `UpdatesViewModel` | update feed, refresh state, saved-search notifications |
| More | `MoreViewModel` | summary counts, provider review state, task/backup/import summaries |
| Paper Detail | `PaperDetailViewModel` | paper, collections, manifestations, tasks, detail actions |
| Download queue | `DownloadQueueViewModel` | persisted task rows, live progress, action state |
| Collections | `CollectionsViewModel` | collection rows and create/rename/delete state |
| Reading & imports | `ReadingImportsViewModel` | local-PDF import state and actions |
| Data & backup | `DataBackupViewModel` | backup export/restore state and Android document effects |
| Sources | `SourcesViewModel` | installed/available/untrusted/orphaned providers and install actions |
| Appearance | `AppearanceViewModel` | built-in/community theme selection and appearance preferences |
| Settings | `SettingsViewModel` | settings preferences and persistence actions |
| Stats | `StatsViewModel` | derived library statistics |
| About/help | `AboutViewModel` only if state is needed | static content and external links |

The reader Activity already has a reader-specific state holder and lifecycle boundary. It will not be
forced into Compose ViewModel plumbing; the reader change is a focused state-machine/chrome refactor.

### Navigation and effects

Routes remain the only place that knows both a destination and the navigation controller. A screen
receives stable callbacks such as `onOpenPaper`, `onBack`, and `onOpenDownloadQueue`; it never sees a
`NavHostController`. ViewModels do not navigate and do not hold an Activity, `Context`, or View.

Android document pickers, notification permission, PackageInstaller, and external URI launches remain
UI effects handled by the composition root or destination route. A ViewModel may emit a typed effect
request, but it must not launch the platform API itself.

### Migration order

1. Introduce shared UI state primitives without changing domain or persistence behavior.
2. Extract screen ViewModels and move existing controllers into their owning screen one destination
   at a time. Keep the existing root screen function signatures stable during each move.
3. Simplify `PaperReaderApp` and `PaperReaderRoutes` after all destinations have an owner.
4. Run host unit/lint/build and connected smoke checks before the plugin/theme/UX branches depend on
   the refactored entry points.

Production Kotlin files remain below 600 lines. Existing large files are split at screen, state, or
lifecycle seams; no pass-through wrapper is added merely to satisfy the line count.

## arXiv readable-document ownership

### Source extension responsibilities

`PaperReader-sources/source-arxiv` owns:

- exact-version arXiv HTML URL construction and bounded HTTP fetch;
- arXiv HTML parsing and document selection;
- provider-specific author, table, link, figure, MathML, and conversion-artifact normalization;
- the provider-owned HTML safelist and executable-markup rejection;
- opaque asset-reference generation and source-side fixture coverage;
- source/license/version/hash metadata returned through the extension contract.

The extension never receives a host filesystem path or Room object. It continues to run in its own
package/UID and verifies the host caller.

### Neutral extension contract

`SourceReadableDocumentMetadata` is extended with a bounded readable-document contract identifier.
Decoding remains backward-compatible for older installed extensions by treating an absent field as
the legacy contract value; the host still applies its final safety gate. New source builds must
publish an explicit contract identifier and update it when provider sanitization changes.

The contract continues to carry:

- request correlation and exact source URL/version;
- source and document SHA-256 values;
- sections and warnings;
- opaque `paperreader-asset://<sha256>` body references and asset metadata.

The wire contract must reject unknown unsafe values, duplicate IDs, invalid hashes, oversized body or
metadata payloads, non-HTTPS URLs, and unsupported media types.

### Host responsibilities

The host replaces the arXiv-specific readable path with a provider-neutral coordinator:

1. Derive a cache identity from provider ID, record ID, version, readable contract, and renderer
   contract. Old built-in/base64 entries are not reused by the new path.
2. Request the document through the installed source extension when it advertises
   `readable_document`.
3. Verify request ID, exact version/source URL, body size, UTF-8, source/document hashes, section
   bounds, warning values, asset IDs, and safe same-directory HTTPS asset URLs.
4. Apply a final deny-by-default structural gate before publication. This is a generic trust gate,
   not an arXiv parser or provider sanitizer.
5. Fetch assets through an independent generic asset lane, with bounded concurrency, cancellation,
   retry/backoff, media-type validation, SVG complexity checks, per-asset byte limits, and cache
   quota enforcement.
6. Replace unavailable asset references with caption-preserving placeholders and a typed warning.
7. Publish body and asset metadata atomically enough that incomplete groups are never readable.
8. Render only app-private validated assets in the network-blocked WebView.

The generic host asset lane may enforce that an asset is HTTPS and in the trusted same-directory
scope of the verified document source URL. It must not contain an `arxiv.org` constant or construct
arXiv URLs.

### Removal and fallback

The host removes the built-in arXiv HTML parser/fetcher path and any provider-specific host class
whose only purpose is arXiv acquisition. Generic asset validation may be retained under provider-
neutral names. A missing/untrusted/unavailable readable capability returns a typed unsupported or
offline result and leaves the original PDF path available; it never silently reactivates a built-in
arXiv parser.

The existing two-lane asset design remains authoritative for quotas, retained offline artifacts,
export, startup reconciliation, and exact-document annotations.

## Theme and design tokens

### Built-in presets

`PaperThemePreset` gains `MIHON`. `NEOBRUTALISM` remains the fallback for missing/unknown preference
values and remains the default value written by new installs. Existing community theme keys remain
compatible and take precedence when a matching validated extension theme is selected.

The Mihon-inspired preset supplies light and dark Material 3 schemes with explicit roles for:

- primary/on-primary and primary container/on-primary-container;
- secondary/on-secondary and secondary container/on-secondary-container;
- tertiary/on-tertiary and tertiary container/on-tertiary-container;
- background/on-background and surface/on-surface;
- surface container lowest/low/default/high/highest;
- outline/outline-variant;
- error/on-error and error container/on-error-container;
- typography, corner, border, and accessibility-related semantic tokens used by PaperReader.

The token model is extended only where a real Material 3 role is consumed. Avoid adding arbitrary
brand colors or a second parallel theme system. Status colors must remain readable in both modes and
must not be the only indication of state.

### Accessibility and visual rules

- Interactive targets remain at least 48 dp, independent of visible border geometry.
- Every semantic color pairing is checked for readable contrast in light and dark modes.
- The default Neobrutalism palette, typography, and icon behavior remain visually stable unless a
  requested UX change explicitly affects them.
- No emoji is used as a structural icon. Existing validated Material Symbols/built-in icon paths
  remain the icon source.
- Community theme validation and the reader palette bridge continue to reject malformed tokens.

## UX changes

### More

More remains a focused hub inspired by Mihon's preference-row model:

- one scrollable list with clear section headings;
- icon, title, concise supporting summary, and a visible forward affordance per row;
- grouped Personalize, Reading, Data & sources, Insights, and About actions;
- live summaries for active/failed downloads, provider review states, backup/import work, and saved
  paper count;
- no duplicate status prose that is already visible inside the destination screen.

The existing download queue's live aggregate progress, per-task progress, state grouping, actions,
and back arrow are preserved. A queue entry must remain reachable with a 48 dp target and a content
description containing its current state/progress.

### Library

The first-level status control becomes an obvious, count-bearing `All`, `Unread`, and `Finished`
selector. `Reading`, `Annotated`, collections, search, and sort remain available through secondary
controls so existing capabilities are not removed from the product.

List and grid cards share a small presentation model:

- title is the strongest field and is clamped without reserving unnecessary empty rows;
- author display uses academic truncation: one author is shown by name, two are joined with “and”,
  and three or more use `First Author et al.`; the complete author list remains available through
  semantics/detail;
- provider/year/primary identifier are compact metadata, not a wall of badges;
- one compact status/progress row is shown only when it carries information;
- the primary read action remains obvious and at least 48 dp, but is not repeated as an oversized
  full card section when the card can be opened directly;
- grid cards omit authors and keep the one-line title rule already documented in `SPEC.md`.

The filter labels and counts are derived from one immutable screen state so counts cannot drift from
the visible list.

### Paper Detail

The header shows title, academic author summary, source/year, and DOI when present. DOI is rendered as
a labeled value with copy/open behavior and a safe `https` resolver; the complete identifier list is
kept for less common identifiers. Manifestations continue to show license, provenance, version,
download state, original PDF, mobile reading, and export actions without duplicating the same state in
decorative badges.

The redundant labels represented by the attached image are removed from the repeated detail/card
presentation. Legal/provenance information remains explicit where it affects trust or reading
decisions.

### Reader chrome

The readable reader keeps the existing toolbar actions, provenance disclosure, find, contents,
annotation, citation return, original PDF, and system Back behavior. Chrome visibility becomes a
small explicit state machine:

- initial/loading/error states keep navigation visible;
- after a meaningful downward scroll past a top-content threshold, toolbar title/subtitle and
  provenance collapse together;
- upward scroll or a deliberate tap restores them;
- chrome does not hide while find is open, while a modal/action mode is active, or near the top of
  the document;
- hidden views are removed from accessibility traversal, while Back and essential actions remain
  available through system/navigation affordances;
- reduced-motion settings disable the transition without changing visibility semantics.

The implementation must not rely on a title overlay that blocks document text or a tap target that
cannot be discovered by TalkBack.

## Branch and review order

Implementation is split into independently reviewable branches:

1. Host `refactor/screen-level-mvvm-udf`: screen state holders, route composition, and no product
   visual changes beyond preserving existing behavior.
2. Host/API and source `feat/arxiv-readable-plugin-boundary`: neutral readable contract version,
   generic host document/asset coordinator, source-owned arXiv path, and cache migration.
3. Host `feat/mihon-theme-tokens`: expanded Material 3 roles and selectable Mihon-inspired preset,
   with Neobrutalism default preserved.
4. Host `feat/library-detail-reader-ux`: More, Library, Detail, author/DOI presentation, redundant
   label removal, and reader chrome behavior.
5. Host/site docs `docs/emulator-evidence-refresh`: final screenshots, README/docs/changelog and
   PaperReader site updates after the merged runtime is verified.

The architecture branch lands before the source/theme/UX branches depend on its screen seams. Source
and UI work may run in parallel only when they do not edit the same files or require an unresolved API
decision. Every agent branch receives a bounded task and is reviewed for both spec compliance and
code quality before integration. Feature branches remain available for comparison and user testing
until the local release gate passes; only then are approved changes merged into `main`.

## Verification contract

### Local host/source gates

Use the repository Gradle wrappers, JDK 21, Android SDK Platform 36/37 as configured by the branch,
and the existing `emulator-5554`. Do not wipe or replace the shared emulator. Run the exact host and
source gates from the repository instructions, including lint and APK assembly. Run connected suites
for changed Android/runtime/UI boundaries locally with the existing application-id suffix policy.

No new GitHub test workflow or GitHub-only functional/benchmark suite is added. Existing parser
fixtures and the nearest existing contract tests remain the deterministic regression gate; if a
changed wire contract lacks coverage, extend the nearest existing fixture test and run it locally,
not as a separate test-only branch.

### Local functional adb audit

Use the installed debug APK as a user would, with `adb` and UI hierarchy/screenshot inspection. The
audit covers at least:

- search and open: Attention, CGP-Tuning, LoRA, Llama 2, ResNet, and InstructGPT;
- save/open/read/back/find/contents/citation return and original-PDF fallback;
- first readable load, cache reopen, offline reopen, export, and process recreation;
- figure captions, large image-heavy documents, SVG with embedded raster data, and explicit warnings
  for missing/unsafe/transiently unavailable assets;
- no arbitrary image-count cap in source and app-private asset metadata;
- PDF request, live percentage, queue persistence, retry/cancel/remove, and queue back navigation;
- Library All/Unread/Finished counts, card actions, long-author semantics, Detail DOI actions, More
  rows, theme switching, light/dark, 130%+ font scale, and reader chrome hide/show.

The audit adds no one-off pixel test files. Screenshots and raw device evidence live outside source
tests and are copied into documentation only after the final local run.

### Local performance benchmark

Record before/after values on the same emulator and paper fixtures:

- cold and warm destination launch time (`am start -W`);
- readable document acquisition time and body byte size;
- asset count, total asset bytes, peak asset concurrency, retry count, and cache publication time;
- reader first-content visibility time, scroll stability/jank evidence from `dumpsys gfxinfo`, and
  WebView process stability;
- Library first-render time with the six-paper fixture set;
- APK size and install/start time.

The benchmark is an evidence script/record, not a committed test suite. Results and environment are
reported in `docs/TESTING.md` after the final merge.

### Security

Security remains on the existing GitHub CodeQL/MobSF/secret/dependency-review workflows and the
host/source trust checks. Local work verifies fail-closed behavior, bounded response/asset sizes,
caller/package/signer validation, no network in the reader WebView, and no storage path leakage. No
new security test runner is introduced.

## Documentation and site handoff

After the merged branch passes the local gate:

- update `docs/SPEC.md` for screen state ownership, theme preset, reader behavior, and plugin-owned
  arXiv HTML;
- update `docs/ARCHITECTURE.md` for screen ViewModels, generic readable assets, and the extension
  contract boundary;
- update `docs/EXTENSIONS.md` for the readable-document contract version and source responsibilities;
- update `docs/TESTING.md` with exact local commands, emulator/API, adb audit, benchmark, asset counts,
  and intentionally absent GitHub functional tests;
- update `README.md` and `CHANGELOG.md` with present-tense shipped behavior and real screenshot paths;
- update relevant third-party attribution for Mihon-inspired tokens if implementation reuses more than
  general design concepts;
- update `paperreader-site` using its existing tactile editorial design system and the final valid
  emulator captures. Site screenshots must reserve dimensions, keep focus/reduced-motion behavior,
  work at 375/768/1024/1440 widths, and contain no emoji structural icons.

Screenshots must be pulled from the emulator as binary PNGs, inspected visually, and checked for
credentials or private paper files before publication.

## Acceptance criteria

The work is complete only when all of the following are true:

- every listed destination has a focused state owner and the root callback matrix is removed;
- host boundary tests/lint/build pass and no `:logic` UI imports or host provider parser imports are
  introduced;
- arXiv readable HTML is fetched/sanitized by the source extension, with no built-in arXiv parser or
  arXiv-specific host fetcher remaining;
- body and assets use separate bounded lanes, no figure-count limit is present, and safety quotas
  remain enforced;
- Neobrutalism remains the default and the Mihon-inspired preset passes light/dark/accessibility
  review;
- Library, Detail, More, reader chrome, DOI, academic author display, and redundant-label changes
  are visible and usable through adb on the declared emulator;
- local functional audit and performance benchmark pass with evidence for the six-paper set;
- feature branches have been reviewed, the final approved changes are merged into `main`, and the
  debug APK is copied to the agreed artifacts directory with package/signature/SHA-256 evidence;
- docs, README, changelog, site, and emulator screenshots describe the merged implementation only.
