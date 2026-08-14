# PaperReader product specification

Status: pre-1.0 product contract. Update this file when product scope, provider roles, identity,
reader behavior, persistence, or release requirements change.

## Product statement

PaperReader is an Android paper library and mobile-first reader. It finds scholarly work from
installable providers, preserves the original publication and provenance, and turns supported papers
into a readable, navigable layout for a phone without pretending that lossy conversion is exact.

Mihon inspired the library, source-extension, update, and reader-navigation model. PaperReader uses
those ideas for scholarly documents rather than copying Mihon's code organization or in-process
extension loading.

## Product goals

- Search real scholarly APIs and preview title, authors, abstract, identifiers, subjects, access,
  license, provider provenance, and citation observations before saving.
- Keep a durable local library, collections, history, reading state, bookmarks, annotations, saved
  searches, downloads, and backups without mock production data.
- Prefer a mobile reflow layout for supported papers while always preserving an honest original-PDF
  fallback.
- Support separately released source, search, metadata, and theme extensions with safe defaults.
- Make provider updates maintainable outside the app release cycle without silent APK installation.
- Ship English UI, accessible touch targets, adaptive navigation, and complete light/dark theme
  palettes.

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

Appearance has independent controls for visual preset and System/Light/Dark mode. Doodle uses Tabler
icons; Neobrutalism uses Material Symbols. A theme extension supplies a complete
semantic icon set with its declarative tokens. Empty-state color is a semantic theme token, never a
hard-coded purple. Status colors keep each preset's accent hue and use AA-safe tones when reused as
text or icons on light surfaces.

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
| arXiv | Content source | arXiv search and exact identifier/version lookup |
| Europe PMC | Content source | Biomedical search and DOI/PMID/PMCID lookup |

The official source repository is
[`ImAno177/PaperReader-sources`](https://github.com/ImAno177/PaperReader-sources). Each extension has
its own package, tests, version, APK, signer, release notes, and registry record. Updating that
repository can publish provider upgrades without rebuilding the host app.

Semantic Scholar owns natural-language search; Crossref owns exact DOI enrichment.

### Federated search

- Exact DOI/arXiv/PMID/PMCID input routes only to extensions that declare the matching identifier.
- Unstructured queries call enabled `SEARCH_ENGINE` providers only; the official default is Semantic
  Scholar. Content and metadata engines remain available for exact supported identifiers.
- Provider requests are cancellable, rate-limited, bounded, and independently fail.
- Results cluster only on exact canonical aliases and preserve provider alternatives.
- Ranking is deterministic: exact identifier match, title/text match, Semantic Scholar citation
  tie-break, publication date, then stable provider-record key.
- Search result cards open the same full metadata preview used by Library before Save/Open.
- Production tests use fixtures and a local server; a separate audit may exercise live APIs.

### Google-to-arXiv handoff

When installed providers return no match, Search may open a Google query constrained to arXiv. The
app does not scrape Google result HTML, embed a Google API key, or treat a search snippet as paper
metadata. The user selects or shares an arXiv `/abs/`, `/html/`, or `/pdf/` URL; Android hands that
URL back to PaperReader, which normalizes the exact arXiv identifier and routes it to the installed
arXiv content source for API metadata and manifestations. Unsupported browser URLs are ignored.

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

- Responsive single-column typography and reversible 85–200% text size.
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
- [arXiv API user manual](https://info.arxiv.org/help/api/user-manual.html)
- [arXiv HTML availability](https://info.arxiv.org/about/reports/2023_arxiv_annual_report.html)
- [Semantic Scholar Academic Graph API](https://api.semanticscholar.org/api-docs/graph)
- [Crossref REST API](https://www.crossref.org/documentation/retrieve-metadata/rest-api/)
- [Europe PMC REST API](https://europepmc.org/RestfulWebService)
- [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)
- [Android app signing](https://developer.android.com/studio/publish/app-signing)
