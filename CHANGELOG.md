# Changelog

This file records user-visible changes to PaperReader. The `Unreleased` section describes the
current branch. Released sections correspond to Git tags.

## [Unreleased]

### Added

- Library cards now expose a full-width quick Read action that opens the mobile reader or a local PDF.
- Paper Detail can download a verified, sanitized readable HTML file beside the PDF action for each
  supported arXiv version.
- Search can open a constrained Google arXiv query inside PaperReader and route a selected result to
  the native arXiv metadata pipeline without launching an external browser.

### Changed

- Paper Detail labels paper representations as versions and files, keeps secondary source and delete
  actions behind progressive disclosure, and presents identifiers in one compact group.
- The Library grid uses a phone-sized minimum column width so the persisted layout toggle produces a
  visibly distinct two-column layout on common handsets.
- Grid cards now prioritize title, discipline, and reading state by omitting secondary author
  metadata, keeping only the text before `:` in long titles, and clamping the result to one line.
  One compact state row and a thin progress strip keep cards equal without large empty regions.
- The search field remains fixed below the page title while results scroll, and Recent searches shows
  at most the five newest matching queries.
- Neobrutalism now uses a softer sun-yellow accent on a warm paper canvas, 5 dp corners, 2 dp
  outlines, a soft sun-yellow warning accent, and a subtle 1 dp bottom shadow. Dark mode uses black
  surfaces and white foreground roles without recoloring accents or status colors.
- Selected primary-navigation destinations use an outline and short icon-scale transition without
  changing the tab fill or label color. Unselected destinations remain transparent.
- Library list and grid cards separate the paper-preview target from the full-width `Read` action.
- Paper Detail uses concise `Read` and `Open PDF` labels, 48 dp reading-status targets, ellipsized
  two-column version metadata, and compact wrapping status badges.
- Source-store cards use progressive disclosure for package, signer, and trust-failure details.
- More and Sources group related rows under one outline instead of boxing every action separately.
  Compact list states, icon-only provider disclosure, and omitted zero-count summaries remove repeated
  copy while preserving 48 dp targets and trust details.
- `Read` actions are text-only, while shortened grid titles still expose the full paper title to
  accessibility services.
- The Search control uses stable placeholder geometry, so its action aligns with the input outline
  from the first frame. Buttons and cards now use a low-opacity 1 dp shadow strip confined to their
  bottom edge, with no lateral offset.
- At 200% text scale, primary navigation switches to fully labelled icon targets instead of truncating
  all five names. Reader toolbars grow with titles and subtitles instead of clipping them.
- Successful readable HTML export can retain the same verified app-private artifact for offline Read
  after restart. Retention is exact-hash and bounded; the user-selected file remains a separate
  shareable copy.
- Reader source and license details collapse to one accessible line, loading has a visible status,
  and wide tables expose a subtle horizontal scrollbar.
- Reader highlights open a single mobile editor where notes can be added, changed, or deleted without
  navigating through nested dialogs. Notes are visually distinct from highlight-only passages.

### Fixed

- Source extensions are preflighted through Binder before they appear usable, so an APK pinned to a
  different host signer is reported as untrusted instead of failing every search.
- Source Binder deadlines now outlive the bounded connection and read timeouts used by extensions.
- All five primary destinations remain reachable in short landscape navigation rails.
- Reading-history removal now requires confirmation and states that the saved paper remains in
  Library. Search and update rows expose non-overlapping button semantics at large text sizes.
- Reader toolbar and find icons remain visible in dark mode, long titles yield space to actions, and
  citation jumps provide a full-width `Back to reading` control reachable by either hand.
- Readable HTML uses theme-aware figure surfaces, two-column author metadata at phone widths, and
  intrinsic wide tables inside their own horizontal scroller.
- The Google fallback runs in an isolated process, accepts only allowlisted HTTPS Google resources,
  and canonicalizes validated Google redirect targets before handing an arXiv ID to native search.
- Empty states no longer wrap their icon and headline in a large tinted card. Completed download rows
  place the status at the right edge of the title and combine task metadata into one compact line.
- Empty update sections stay compact, empty-state headings keep their violet accent in both modes,
  and unfilled actions use readable neutral foreground colors instead of pale amber text.
- The Search action exposes one accessible clickable target instead of placing its label on a nested
  decorative icon.
- Search no longer shows a disabled saved-search card when no provider is available.
- Natural-language title searches now rank an anchored canonical title ahead of newer papers that
  only mention the same phrase. Existing exact-identifier matches now reopen their saved Library
  paper after a restart, and legacy arXiv affiliation rows no longer appear as authors.
- Search, source refresh, local import, reading-status, and background-update state changes expose
  stable, grouped, live accessibility semantics.
- Running search and history-removal controls now expose specific accessible names, and clickable
  shared surfaces enforce a 48 dp minimum target.
- In-paper search keeps the active match below the top WebView edge instead of clipping its first
  line.
- Toolbar and system Back return from a citation before leaving the reader. The full-width return
  action uses lower elevation and respects the system reduced-motion setting.

### Security

- Updated jsoup to 1.23.1, constrained dependency submission to packaged runtime configurations, and
  added CodeQL plus grouped twice-monthly security updates to the source-extension repository.
- Added the GitHub-verified MobSF source scan with pinned action revisions and SARIF upload to Code
  Scanning.
- Hardened the Android entry point with an empty task affinity and `singleTop`, disabled cleartext
  traffic and WebView debugging, made the test manifest non-backupable, and removed unverified arXiv
  App Link claims that the project cannot host with `assetlinks.json`.

### Build

- Centralized the host unit-test and lint commands in root Gradle tasks. Release builds now require a
  successful Android CI run for the exact commit instead of rerunning the host gate.
- Pull-request CI now runs host tests, lint, and debug assembly in one Gradle invocation. Coverage,
  unsigned release APKs, and SBOM packaging run on main or manual builds instead of every review.

### Removed

- The built-in Doodle preset, its Tabler icon pack, and the associated bundled assets and notices.
  Existing community-theme contracts remain compatible.
- The duplicate readable HTML download command from the Reader overflow menu.

## [0.1.6] - 2026-08-15

### Fixed

- Search now keeps recent queries, exposes per-provider status and filters, and lets users retry an
  unavailable provider without losing successful results from other sources.
- Natural-language searches now include discovery-capable arXiv and Europe PMC content sources when
  Semantic Scholar is rate-limited, instead of returning only a provider failure.
- arXiv free-text requests preserve the submitted phrase as a title query, avoiding the API's broad
  space-separated `all:` OR expansion.
- Provider User-Agent strings identify the public project URL, and source extensions apply bounded
  exponential cooldowns when a 429 response omits `Retry-After`.

## [0.1.5] - 2026-08-14

### Added

- Google-to-arXiv browser handoff. Search can open a constrained Google query when installed
  providers return no match, and Android can route an arXiv `/abs/`, `/html/`, or `/pdf/` link back to
  PaperReader.
- Exact arXiv URL parsing that preserves the work identifier and revision before the installed arXiv
  source performs API lookup.
- Documentation rules in `AGENTS.md` and a current feature matrix in `docs/SPEC.md`.

### Changed

- Saved papers with identifier-only records repair their readable manifestation before the detail
  screen offers the mobile reader.
- Paper Detail uses a full-width reading action, compact reading status, and a two-by-two manifestation
  metadata grid. This keeps the action reachable for either hand and prevents long labels from being
  clipped.
- The mobile reader exports sanitized, verified HTML and keeps wide tables horizontally scrollable.
- Author metadata uses a two-column layout where the source provides multiple authors.

### Fixed

- arXiv HTML tables no longer shrink to the viewport width and lose columns.
- Legacy saved records no longer lose their read action when the original search result had no
  manifestation data.

## [0.1.4] - 2026-08-14

### Added

- Separate source and theme extension APKs over the versioned extension API.
- Signed extension stores, APK hash and signer verification, user-confirmed installation, update
  notifications, orphan and untrusted states, CodeQL, Dependabot, and SPDX SBOM generation.
- Local library, saved searches, downloads, metadata backups, verified arXiv HTML caching, and the
  mobile reader workflow.

### Changed

- Provider roles are explicit: Semantic Scholar searches, Crossref enriches exact DOIs, arXiv owns
  arXiv content, and Europe PMC owns biomedical content.
- Doodle and Neobrutalism themes use their own semantic palettes and icon families.

## [0.1.3] - 2026-08-14

### Fixed

- arXiv author metadata is readable on narrow mobile screens, including affiliations and email lines.

## [0.1.2] - 2026-08-14

### Fixed

- Bottom navigation hit targets and search scrolling remain stable during long result lists.

## [0.1.1] - 2026-08-14

### Added

- Provider, reader, extension, library, download, backup, and update workflows used by the first
  public pre-1.0 builds.

## [0.1.0] - 2026-08-14

### Added

- The separate-extension model and the first signed source-update contract.

[Unreleased]: https://github.com/ImAno177/PaperReader/compare/v0.1.6...HEAD
[0.1.6]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.6
[0.1.5]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.5
[0.1.4]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.4
[0.1.3]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.3
[0.1.2]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.2
[0.1.1]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.1
[0.1.0]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.0
