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
  metadata, keeping only the text before `:` in long titles, and clamping the result to two lines.
  One compact state row and a thin progress strip keep cards equal without large empty regions.
- The search field remains fixed below the page title while results scroll, and Recent searches shows
  at most the five newest matching queries.
- Neobrutalism now uses a softer sun-yellow accent on a warm paper canvas, 5 dp corners, 2 dp
  outlines, and grounded 2 by 4 dp hard shadows. Dark mode uses black surfaces and white foreground
  roles without recoloring accents or status colors.
- Selected primary-navigation destinations use an outline and short icon-scale transition without
  changing the tab fill or label color. Unselected destinations remain transparent.
- Library list and grid cards separate the paper-preview target from the full-width `Read` action.
- Paper Detail uses concise `Read` and `Open PDF` labels, 48 dp reading-status targets, ellipsized
  two-column version metadata, and compact wrapping status badges.
- Source-store cards use progressive disclosure for package, signer, and trust-failure details.
- More, Appearance, Sources, and import screens now use compact rows and concise labels. Repeated
  provider metadata is hidden unless it affects an action or trust decision.
- `Read` actions are text-only, while shortened grid titles still expose the full paper title to
  accessibility services.
- Neobrutalist controls use a restrained downward shadow with no horizontal offset, and the Search
  action aligns with the full height of its input field.

### Fixed

- Source extensions are preflighted through Binder before they appear usable, so an APK pinned to a
  different host signer is reported as untrusted instead of failing every search.
- Source Binder deadlines now outlive the bounded connection and read timeouts used by extensions.
- All five primary destinations remain reachable in short landscape navigation rails.
- Reading-history removal now requires confirmation and states that the saved paper remains in
  Library. Search and update rows expose non-overlapping button semantics at large text sizes.
- Reader toolbar and find icons remain visible in dark mode, long titles yield space to actions, and
  citation jumps provide a full-width `Back to reading` control reachable by either hand.
- Readable HTML uses theme-aware figure surfaces, two-column author metadata with a narrow-screen
  fallback, and intrinsic wide tables inside their own horizontal scroller.
- The Google fallback runs in an isolated process, accepts only allowlisted HTTPS Google resources,
  and canonicalizes validated Google redirect targets before handing an arXiv ID to native search.
- Empty states no longer wrap their icon and headline in a large tinted card. Completed download rows
  place the status at the right edge of the title and combine task metadata into one compact line.
- Running search and history-removal controls now expose specific accessible names, and clickable
  shared surfaces enforce a 48 dp minimum target.

### Security

- Updated jsoup to 1.23.1, constrained dependency submission to packaged runtime configurations, and
  added CodeQL plus grouped twice-monthly security updates to the source-extension repository.
- Added the GitHub-verified MobSF source scan with pinned action revisions and SARIF upload to Code
  Scanning.

### Build

- Centralized the host unit-test and lint commands in root Gradle tasks. Release builds now require a
  successful Android CI run for the exact commit instead of rerunning the host gate.

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
