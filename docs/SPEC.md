# PaperReader product specification

Status: pre-1.0 product contract. This file describes the host behavior implemented on the current
branch. Update it when product scope, provider roles, identity, reader behavior, persistence, or
release requirements change.

## Product statement

PaperReader is an Android paper library and mobile-first reader. It finds scholarly work from
installable providers, preserves the original publication and provenance, and turns supported papers
into a readable, navigable layout for a phone without pretending that lossy conversion is exact.

Mihon inspired the library, source-extension, update, and reader-navigation model. PaperReader uses
those ideas for scholarly documents rather than copying Mihon's code organization or in-process
extension loading.

## Current feature set

The following behavior is implemented in the host. Provider implementations are installed separately
and may be unavailable until the corresponding signed extension is installed.

| Area | Implemented behavior |
| --- | --- |
| Discovery | Semantic Scholar, arXiv, and Europe PMC free-text search; Crossref exact DOI enrichment; deterministic exact-identifier routing and citation-aware ranking. |
| Google handoff | A hardened in-app `site:arxiv.org/abs` search surface; explicit `/abs/`, `/html/`, or `/pdf/` URL handoff to exact arXiv ID and version parsing. |
| Library | Room-backed papers, versions and files, full-width quick Read actions, list/grid layouts, collections, reading status, history, bookmarks, annotations, saved searches, update snapshots, downloads, local PDF import, and metadata backup. |
| Mobile reader | Verified arXiv HTML cache, sanitization, offline WebView rendering, table-of-contents navigation, in-document search, citation return, text/layout controls, figures, MathML, and horizontal table scrolling. |
| Paper files | Per-version PDF download and readable HTML export from Paper Detail. HTML export uses the same verified, sanitized document artifact as the mobile reader. |
| Original documents | Bounded PDF download and an in-app PDF reader with search, page navigation, progress, and bookmarks. |
| Extensions | Separate source and theme APKs over versioned AIDL, signed stores, package verification, user-confirmed PackageInstaller flows, update/orphan/untrusted states, and community extension discovery. |
| Appearance and access | English UI, a Neobrutalism preset, complete community themes, System/Light/Dark mode, adaptive navigation, and 48 dp semantic touch targets. |
| Privacy and safety | Local-first metadata and reading state, no analytics or advertising SDK, bounded network access, sanitized remote HTML, provenance and license retention, and exact-document annotation anchors. |

## Release scope and status

The host is pre-1.0. The feature table above is the current contract. Work listed in the deferred
sections is not shipped and must not be presented as available in the UI or README.

## Non-goals for 1.0

- Silent, privileged, root, or Shizuku extension installation.
- Loading third-party DEX/JAR/JavaScript in the host process.
- Rendering arbitrary remote HTML or compiling untrusted TeX in the app process.
- Cloud-only paper parsing, fabricated extracted text, or inferred content licenses.
- Fuzzy automatic merging based only on title or authors.
- Full collaboration, social features, or hosted sync.

## Information architecture

Root destinations are Library, Search, Updates, History, and More. More groups settings into branches
rather than one long page:

- Appearance
- Collections
- Reading & imports
- Updates & notifications
- Data & backup
- Sources & extensions
- About

Root titles are large, left aligned, and near the top safe area. They have no decorative divider.
Bottom-navigation outlines align with the content grid, while independent touch targets remain at least
48 dp and extend safely around the visible geometry.

Appearance has independent controls for visual preset and System/Light/Dark mode. The built-in
Neobrutalism preset uses Material Symbols, a restrained sun-yellow accent, 5 dp corners, 2 dp
outlines, and subtle 1 dp bottom-only shadows. Dark mode inverts neutral surfaces, text, outlines,
and shadows while preserving accent, container, selection, and status colors. A theme extension
supplies a complete semantic icon set with its declarative tokens. The legacy `DOODLE` extension
decoration remains readable for binary
compatibility but is not a built-in preset. Empty-state headlines are unframed and keep the same
accessible violet accent in light and dark modes; sun yellow is reserved for the icon tile, primary
action, and small state accents. Library grid cards keep titles to one line, omit authors, and use
one compact state row instead of
reserving empty rows for status, annotations, and progress.

## Domain model

### PaperWork

The intellectual work: canonical identifiers, title, authors, abstract, subjects, publication date,
and provider observations.

### PaperManifestation

A concrete representation or revision: provider, upstream version, source URL, content kind, access,
license statement, acquisition time, and original-PDF locator.

### LocalArtifact

An app-private original PDF or generated readable artifact with exact SHA-256, byte size, format,
generator/sanitizer version, provenance, and creation time.

### Identity rules

- Normalize DOI, modern/legacy arXiv identifiers and revisions, PMID, and PMCID.
- Merge automatically only when two records share an exact canonical identifier.
- Keep provider-specific IDs scoped to their authority.
- Preserve distinct arXiv manifestations and exact revisions.
- Treat similar titles as review candidates, never merge proof.
- Keep citation counts as timestamped provider observations; do not overwrite canonical metadata.

Room is the local source of truth. Schema changes increment the version, export the schema, and add a
migration test. Multi-table mutations and identity merges are transactional.

## Provider model

Providers are installed Android APKs from a separate repository and declare one or more roles:

- `SEARCH_ENGINE`: unstructured discovery and ranked result pages.
- `CONTENT_SOURCE`: search/lookup plus a concrete readable or downloadable manifestation.
- `METADATA_ENGINE`: identifier-bound enrichment only.

Official defaults:

| Extension | Roles | Routing policy |
| --- | --- | --- |
| Semantic Scholar | Search engine | Default free-text discovery and citation observations |
| Crossref | Metadata engine | Exact normalized DOI only; never fuzzy discovery |
| arXiv | Content source | Phrase-aware title search plus exact identifier/version lookup and manifestations |
| Europe PMC | Content source | Biomedical discovery, DOI/PMID/PMCID lookup, and manifestations |

The official source repository is
[`ImAno177/PaperReader-sources`](https://github.com/ImAno177/PaperReader-sources). Each extension has
its own package, tests, version, APK, signer, release notes, and registry record. Updating that
repository can publish provider upgrades without rebuilding the host app.

Semantic Scholar is the preferred natural-language search engine. arXiv and Europe PMC may also
contribute discovery results when they advertise the discovery capability; this keeps authoritative
content sources useful when a ranking engine is rate-limited or unavailable. Crossref owns exact DOI
enrichment only.

### Federated search

- Exact DOI/arXiv/PMID/PMCID input routes only to extensions that declare the matching identifier.
- Unstructured queries call enabled providers that advertise `DISCOVERY` and are either `SEARCH_ENGINE`
  or `CONTENT_SOURCE`; Semantic Scholar remains the preferred ranking engine. Metadata-only engines
  remain available for exact supported identifiers.
- Provider requests are cancellable, rate-limited, bounded, and independently fail.
- Results cluster only on exact canonical aliases and preserve provider alternatives.
- Ranking is deterministic: exact identifier match, title/text match, Semantic Scholar citation
  tie-break, publication date, then stable provider-record key.
- The search surface keeps the eight most recent submitted queries locally, exposes source-aware
  `All sources` and `Has results` filters, and reports loading, success, and failure per provider
  with a retry action. A source failure never hides successful results from another provider.
- Search result cards open the same full metadata preview used by Library before Save/Open.
- Production tests use fixtures and a local server; a separate audit may exercise live APIs.

### Google-to-arXiv handoff

When installed providers return no match, Search may open a Google query constrained to arXiv inside
PaperReader. The WebView runs in the isolated `:google_search` process, uses the installed Android
System WebView User-Agent, permits only allowlisted HTTPS Google navigation/resources, exposes no
JavaScript bridge or local files, and blocks mixed content and popups. JavaScript is enabled only for
this isolated surface because Google no longer serves a usable no-JavaScript search flow. Selecting an
arXiv `/abs/`, `/html/`, or `/pdf/` result, including a validated Google redirect, closes the web
surface, normalizes the exact identifier, and routes it to the installed arXiv source for API metadata
and manifestations. Google may require an interactive anti-abuse challenge. The app does not parse
Google result HTML, embed an API key, bypass that challenge, or treat snippets as paper metadata.

### Network policy

Use official HTTPS APIs with an honest User-Agent/contact where required. Bound request time, body
size, concurrency, redirects, and retry count. Model offline, rate-limited, unavailable, and invalid
responses as typed outcomes. Respect `Retry-After`; never retry a cancellation or create a storm.

## Mobile reader

The reader is the primary product surface. The PDF viewer remains the fidelity fallback.

### Acquisition order

1. Provider-supplied structured full text from a trusted official host.
2. Official arXiv HTML for the exact manifestation and revision.
3. A future isolated TeX conversion result.
4. Local PDF extraction into a versioned reflow artifact.
5. Original PDF.

The app always retains provenance, source/version/license disclosure, and an original-PDF fallback
when available. It never describes an unsupported conversion as complete.

### arXiv HTML

For an arXiv manifestation, resolve the exact `/html/{id}vN` document. Fetch only trusted arXiv hosts
with byte/time/count limits. Sanitize before storage; remove executable markup and unsafe URLs while
retaining headings, paragraphs, lists, tables, citations, MathML, and bounded same-document figures.
Store an app-private artifact with sanitizer version and SHA-256.

The renderer is a non-exported, network-blocked WebView with a deny-by-default CSP. JavaScript is off
except for short, app-owned commands required for bounded find, selection, and anchor navigation,
then disabled again.

The layout must provide:

- Responsive single-column typography and reversible 85-200% text size.
- Native table of contents and a find bar reachable without scrolling to the top.
- Scrollable wide tables/math and responsive figures.
- Citation jumps with a visible Back to reading position action.
- Stable block/source anchors, selectable text, and exact-hash annotations.
- Offline reopening from verified cache.
- Original PDF action always reachable when the file exists.

### Original PDF

The original viewer supports search, zoom, page position, validated page jump, exact-file progress,
reading sessions, and page bookmarks. Text highlights remain disabled until the PDF renderer exposes
a stable selection/source-map contract.

### Cache

Cache keys include manifestation/version, source URL, extractor/sanitizer version, and content hash.
Writes are atomic and integrity-checked before reuse. Eviction removes complete least-recently-used
artifact groups, never library metadata, annotations, or the user's original PDF. Cache publication
failure must not discard the currently verified in-memory document.

## Extension ecosystem

### Trust model

- Official and user stores are Ed25519-signed strict JSON indexes.
- The host pins the official URL, store ID, and public key.
- Sequence numbers are monotonic; same-sequence different-content indexes are rejected.
- Last-known-good verified data is written atomically.
- User stores require explicit public-key fingerprint confirmation.
- Every APK is checked against signed SHA-256, byte size, package, versionCode, signer certificate,
  extension kind, API compatibility, and exported service descriptor.
- Source extensions run as separate packages/UIDs over bounded AIDL.
- Theme extensions are declarative and include every required semantic icon.

### Update lifecycle

PaperReader uses Mihon's installed, available, update, untrusted, and orphaned states together with its
package-broadcast reconciliation pattern. It adds cryptographic artifact verification before
installation:

1. Refresh stores on cold start, manual refresh, and constrained periodic work.
2. Compare package name, versionCode, contract compatibility, and signer.
3. Notify about compatible updates; never install automatically.
4. Queue a bounded download after the user chooses Install/Update.
5. Verify the APK and open an Android `PackageInstaller` session.
6. Handle user-confirmation, success, cancel, and failure states explicitly.
7. Rescan packages after add/replace/remove broadcasts and reconcile provider state.

Missing registry entries mark installed extensions orphaned. They are not silently disabled or
uninstalled. A signer mismatch is untrusted and cannot be activated.

### Release repository

The provider repository must publish:

- Four independently signed release APKs.
- A signed registry containing compatibility, roles, identifiers, sorts, APK URL, SHA-256, size, and
  signer certificate fingerprint.
- Deterministic parser/request tests and static analysis.
- An SPDX SBOM, dependency review, secret scanning, and twice-monthly Dependabot updates.
- A release workflow that updates the registry only after signed APK verification succeeds.

## Library, updates, and backup

- Saving a search result is explicit; opening a preview does not write Room.
- Collections are many-to-many and deleting a collection never deletes papers.
- Saved searches persist the exact provider snapshot and isolate per-provider failures.
- Background saved-search refresh is opt-in, network-constrained, sequential across searches, and
  notifies only for newly unread results.
- Downloads and extraction are persisted tasks. Cancellation wins commit races and cannot be
  overwritten by late completion.
- Metadata backup is bounded, versioned, validated, and excludes credentials, caches, extension APKs,
  and filesystem paths. Restore requires preview and confirmation.
- Local PDF import copies and validates content into app-private staging before confirmation and
  survives process recreation.

## Accessibility and UX acceptance

- All interactive semantics meet a 48 dp minimum target independent of visible border geometry.
- Navigation remains usable at 130% system font scale; labels do not collide or wrap unexpectedly.
- Light/dark palettes meet WCAG contrast for body/status text and do not encode state by color alone.
- Loading, empty, offline, rate-limited, invalid, permission, cancelled, and unavailable states are
  explicit English copy with a relevant recovery action.
- Search is reachable from every root destination without depending on a fragile bottom-item hitbox.
- Reader find, contents, citation return, text size, and original PDF remain reachable during reading.
- System/Light/Dark mode persists independently from the visual theme.

## Security and privacy

- No analytics, proprietary SDK, credential, paper upload, or private endpoint is added by default.
- Remote content is treated as hostile. Bound size/time, sanitize, and preserve cancellation.
- Never expose host database or storage paths through AIDL.
- Provider API keys, if later supported, use Android-protected storage and are never placed in backup.
- Vulnerabilities are reported through `SECURITY.md`, not public issues containing exploit details.
- Licenses and icon/content attributions are maintained in `THIRD_PARTY_NOTICES.md`.

## Release acceptance

A release candidate is not product-ready until all of the following pass:

- Host unit tests, lint, assemble, dependency review, SBOM generation, and release signing.
- External provider fixture tests, lint, signed APK/registry verification, and SBOM generation.
- Connected UI tests on a declared emulator/API without wiping the existing device.
- Real API audit for Semantic Scholar, Crossref, arXiv, and Europe PMC with rate-limit-safe requests.
- Install/update/cancel/failure/orphaned/untrusted extension flows through Android PackageInstaller.
- Download and mobile-reader audit of at least `arXiv:2501.04510` and `arXiv:1706.03762`.
- English-only copy audit, accessibility scan, dark/light theme review, and navigation hit-target audit.
- Public repositories, CI, Dependabot, LICENSE/NOTICE, README screenshots, tagged APK release, and
  reproducible handoff notes.

Known or deferred behavior must be documented and surfaced honestly. A stub, mock provider, unchecked
APK, placeholder reader, or passing screenshot alone does not satisfy this contract.

## Primary references

- [Mihon extension API update flow](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt)
- [Mihon extension manager](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt)
- [Mihon extension installer](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionInstaller.kt)
- [Mihon global-search toolbar and source filters](https://raw.githubusercontent.com/mihonapp/mihon/master/app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchToolbar.kt)
- [Mihon per-source search states](https://raw.githubusercontent.com/mihonapp/mihon/master/app/src/main/java/eu/kanade/presentation/browse/GlobalSearchScreen.kt)
- [Android Compose SearchBar guidance](https://developer.android.com/develop/ui/compose/components/search-bar)
- [arXiv API user manual](https://info.arxiv.org/help/api/user-manual.html)
- [arXiv HTML availability](https://info.arxiv.org/about/reports/2023_arxiv_annual_report.html)
- [Semantic Scholar Academic Graph API](https://api.semanticscholar.org/api-docs/graph)
- [Crossref REST API](https://www.crossref.org/documentation/retrieve-metadata/rest-api/)
- [Europe PMC REST API](https://europepmc.org/RestfulWebService)
- [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)
- [Android app signing](https://developer.android.com/studio/publish/app-signing)
