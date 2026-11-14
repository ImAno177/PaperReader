# Changelog

This file records user-visible changes to PaperReader. The `Unreleased` section describes the
current branch. Released sections correspond to Git tags.

## [Unreleased]

### Changed

- Search result cards show Save as an outlined button aligned to the end of the card instead of a
  full-width orange button. The orange primary action stays on the search submit button. Saved papers
  keep an Open button in the same position.
- The Data and backup screen uses the orange primary button only for Create library backup. Create
  backup for metadata is now an outlined button. Both labels are unchanged.
- The Library list and grid headers read Papers instead of the sort name. The paper count stays
  beside the title.

## [0.2.1] - 2026-11-14

### Changed

- The Neobrutalism theme uses a flat warm paper palette: cream background, off-white cards,
  brown-black ink and outlines, and 12 dp corners, with no offset shadows. Orange marks the main
  action and in-progress or new states, yellow marks the selected item, and sky blue marks informational
  badges. Dark mode keeps the same warm hue instead of mixing a navy background with gray cards,
  and reader links use a readable rust color.
- Library cards no longer show a field badge. List cards show the source, year, and identifier, and
  grid cards show the source and year, so the grid metadata line stays readable without truncation.
- Library status filter chips wrap onto additional lines, so every chip is reachable at large font
  sizes. The collection chips stay on one row that scrolls horizontally, with a fade at the trailing
  edge while more collections are off screen.
- The primary navigation bar keeps its labels at large font scales. Labels truncate to one line with
  an ellipsis, and the tap targets keep their 48dp minimum height.
- The paper detail Read bar has a top rule that matches the navigation bar.
- The Updates screen's empty saved-search state has a Search papers action that opens Search.
- Extension install and update buttons in Sources size to their content and sit at the trailing edge
  of each release row instead of spanning the full card width. Their states and labels are unchanged.

### Fixed

- The readable reader hides its loading overlay only after the first frame of the loaded document is drawn, so a blank page no longer shows while the document appears.
- Empty arXiv separator rules in the readable reader no longer draw an empty bordered box between the abstract and the first section.
- Broken figure images in the readable reader no longer draw an empty bordered frame; only the image's native alt text, if any, remains.
- The readable reader's Contents, Find, and Annotations actions open only after the document has finished loading, so a tap during loading no longer leaves the overlay stuck.

### Build

- Android CI runs only on demand (`gh workflow run android-ci.yml --ref <branch>`), and CodeQL
  runs only when dispatched manually or called by the release workflow. Pushes, pull requests, and
  the weekly schedule no longer start them. Dependency review runs on demand against `main`.
- The release workflow runs the Android CI gate and CodeQL itself, and publishes only after both
  pass for the release commit. It no longer looks up a successful main CI run.
- The release workflow accepts `dry_run=true`. A dry run validates inputs, runs the Android gate and
  CodeQL, and skips the release job, so no tag, release, asset, or signing secret is used.
- `hostLint` now fails on any comment in code (Kotlin, Kotlin script, Java, and AIDL sources),
  and the existing comments are removed. The error lists each location and says where
  lasting rationale belongs instead.
- `:app` connected Android test tasks fail before any device work when
  `paperReaderConnectedTestApplicationIdSuffix` is not set. The error gives the `.uitest` command, so a
  run cannot replace the production-signed app or wipe the library.

## [0.2.0] - 2026-11-12

### Added

- Full library backup with streamed, hash-verified PDFs, restore preview, durable recovery, and
  preservation of existing local documents. A missing or changed stored PDF is reported and left
  out instead of failing the whole backup.
- BibTeX/RIS import and export with exact-identifier matching and ambiguity warnings, plus Markdown
  notes export with original quotes and exact document/anchor references. Exact-match imports add
  identifiers and links without replacing saved titles or authors, and BibTeX export keeps works
  with several DOIs by writing the publisher DOI.
- Separate per-version labels for cached HTML, retained offline HTML, and an original PDF.
- A dedicated More hub with grouped settings and a focused Download queue showing aggregate and
  per-task progress, retry/cancel/remove actions, completed work, and a visible back action.
- Five built-in color themes sit alongside the Neobrutalism preset; color mode stays
  independently selectable as System, Light, or Dark.
- A versioned readable_document extension capability. The arXiv source now owns structural HTML
  sanitization while the host receives bounded Binder chunks and materializes same-document assets
  through a separate lane.

### Changed

- Neobrutalism uses neobrutalism.dev's default theme: a pale-blue background with white cards and
  inputs, black ink, 2 dp borders, 5 dp corners, opaque 4 dp offset shadows, a main blue that
  carries black text, and DM Sans. Pressed controls move into their shadow, dark mode uses the
  project's navy theme with light outlines for 3:1 boundary contrast, root headers sit on the
  background, and the bottom navigation has a top rule.
- Community themes with a non-zero shadow offset now draw an opaque diagonal hard shadow in their
  hard-shadow color, and their status badges use the border color for the outline.
- Search previews keep Save/Open visible while metadata scrolls, saved-search controls take less
  space, and unreachable-source messages point to connection checks and retry.
- Root tabs restore search and navigation state. Library grid titles wrap fully, zero-percent
  progress bars are hidden, and long reader author details collapse without changing cached content
  or annotation offsets.
- Room schema 5 and backwards-readable metadata backups preserve year-only publication values
  without inventing a month or day.
- Blocked sources show recovery guidance while keeping certificate and protocol diagnostics in
  expandable details.
- Extension SDK 0.2.0 uses contract API 2 for readable-document Binder calls. API 1 APKs require an
  update; the host checks the separate v2 official store and rejects incompatible service metadata.
- Screens and Compose tests use the same state/action interfaces; obsolete overloads, duplicated
  fixtures, and redundant contract assertions are removed. Reader cache models have their own file.
- Shorter labels and recovery messages across search, library, reader, imports, and backup; technical
  diagnostics stay secondary and destructive-action warnings remain explicit.
- A smaller More header, wrapping download actions, two-line settings summaries, and shorter result
  previews keep existing controls readable on compact screens. Library search focuses on opening
  and dismisses the keyboard when closed or submitted.
- The mobile reader uses a compact find counter with a full spoken description; reader text-size
  controls now have descriptive accessibility labels. Google search uses the app canvas while loading.
- Library filters expose counts; list cards summarize authors; Paper Detail labels DOI and license
  values explicitly; redundant manifestation badges are reduced to the useful local-file state.
- The readable reader hides chrome during downward scrolling and restores it on upward scrolling or
  interaction, while preserving native search, contents, provenance, and Back controls. The chrome
  now lives in a fixed overlay, so hiding it no longer remeasures the WebView or shifts document text.
- Bottom navigation spans the full width, library list cards place Read beside their badges, filter
  chips are shorter with 48dp touch targets, progress bars render as one track, and history removal
  sits inside each card.

### Fixed

- Paper details keep the space in "+ N more" author summaries and label provider record IDs, such as
  "arXiv record 2607.11940v1", instead of showing them as duplicate lowercase identifiers.
- Full backup restore recovers missing PDFs even when their database paths remain, while preserving
  existing files and exact document anchors.
- Reader contents links leave the target heading below the toolbar; source-supplied list markers
  no longer render twice, and multi-paragraph list items retain their content alignment.
- Native reader surfaces and link colors follow the Neobrutalism light and dark palettes.
- Search previews omit duplicate provider aliases while preserving the original provider records.
- Existing installations migrate the pinned official source store to its API 2 URL only after
  signature, identity, rollback, and equivocation validation succeeds.
- Metadata and PDF picker results survive Activity recreation; metadata archive parsing runs off Main.
- Bibliography imports reject aliases owned by multiple works instead of silently choosing one.
- Archive cancellation closes blocked input streams, and interrupted restores clean only unreferenced
  task-owned files.
- Readable HTML export restores the exact cached document when the screen is recreated while the
  save picker is open. Missing or changed documents report an export failure.
- Cancelling a source search no longer reports it as an unavailable provider.
- The extension SDK packages the readable-document AIDL callback required by source services.

### Build

- The `external/` submodules point to the API 2 source catalog and to sources and samples built with
  Android Gradle Plugin 9.5.1, so `tools/verify-extensions.ps1` passes against the current host.
- Removed 4 tautological host unit tests, narrowed 2 field-readback tests to their one real
  default, and merged 2 duplicate navigation cases (145 lines removed, 9 added). Coverage gates
  are unchanged.
- Host releases start as prereleases without replacing the latest stable download. Promotion follows
  signed source-catalog publication and verification of fresh installs and upgrades.
- Related source, sample, theme, and site repositories are pinned as optional Git submodules under
  `external/`, with a local extension compatibility gate against the host SDK.
- Documentation-only pull requests skip Android setup and Gradle while retaining their existing
  check names, documentation validation, dependency review, and security scans. Main and manual
  runs still execute the full host gate and produce release artifacts.
- Centralized dependency and build-tool pins in the Gradle version catalog without upgrading them;
  CI, CodeQL, and release share one Android setup action.
- Same-repository pull requests write a PR-scoped Gradle cache, so later pushes reuse dependencies
  and build outputs instead of rebuilding from an empty cache; fork pull requests remain read-only.
- Release inputs are validated before signing, use environment variables, and require green main
  push CI for the exact commit. Signing keys are cleaned up after success or failure.
- CI validates workflow syntax and uploads test and lint diagnostics on failure; APK artifacts skip
  redundant compression. Combined coverage fails clearly without connected data or class inputs.
- Readable-document contract tests cover bounded inputs, malformed payloads, and Android Bundle
  compatibility under the existing 100% merged line and branch coverage gate.
- Android CI and CodeQL now use the maintained Android SDK setup action and no longer request the
  removed legacy `tools` package.
- Updated Gradle 9.8.2, Android Gradle Plugin 9.5.1, Kotlin 2.4.21, Navigation 2.10.2, DataStore
  1.3.0, WorkManager 2.12.0, OkHttp 5.5.1, kotlinx.serialization 1.12.0, Tink 1.24.0, and pinned
  GitHub Actions. Coroutines stay on 1.11.0 because the 1.12.0 core artifact is not published.

## [0.1.11] - 2026-09-06

### Changed

- Readable arXiv papers now fetch HTML and figures through separate bounded lanes. Figures are
  stored as independently validated app-private assets, served locally to the offline reader, and
  inlined only when exporting HTML. Transient asset throttling is retried with bounded backoff. The
  former image-count circuit breaker is removed; per-asset, SVG-complexity, metadata-manifest, and
  cache-byte limits remain.

### Fixed

- Image-heavy papers such as CGP-Tuning, Attention, LoRA, Llama, ResNet, and InstructGPT no longer
  lose valid figures because the HTML body was inflated by base64 embedding or an arbitrary count
  limit. Strictly sanitized SVG figures may retain embedded raster data images. Missing assets now
  preserve captions and surface an explicit unavailable-figure warning.

## [0.1.10] - 2026-09-06

### Added

- More now groups download queue, reading stats, searchable settings, help, and existing data
  management in one focused hub while retaining PaperReader's neobrutalist surfaces.
- Appearance now controls library layout, large-screen navigation, date/relative timestamps, and
  figure visibility in mobile papers. Reader settings expose persistent text size, spacing, and
  margin defaults.

### Changed

- Readable HTML retains the verified app-private artifact before opening the optional export picker,
  downloads HTML and same-document assets through separate bounded lanes, embeds validated SVG and
  raster figures with lazy decoding, and degrades oversized or excess figures to labeled placeholders
  instead of risking a WebView memory crash. The reader still opens only the app-private artifact;
  exported HTML remains a separate shareable copy.
- Readable-paper reading supports native two-finger pinch zoom while keeping the existing text-size
  preference and hiding legacy on-screen zoom controls.

## [0.1.9] - 2026-08-25

### Fixed

- Readable HTML now opens from the verified app-private cache without scanning and pruning every
  cached manifest on each Reader load. Export retention also reuses the already verified document
  instead of rereading and rehashing its full body.
- Source release UI assertions now select the intended Semantic Scholar release when multiple
  source cards expose the same provider name.

### Changed

- Reader, source-contract, DAO, and use-case modules were split into focused files to make the
  codebase easier to navigate and maintain.

## [0.1.8] - 2026-08-21

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
- Hardened the Android entry point with an empty task affinity and `singleInstance`, disabled cleartext
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

[Unreleased]: https://github.com/ImAno177/PaperReader/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/ImAno177/PaperReader/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/ImAno177/PaperReader/compare/v0.1.11...v0.2.0
[0.1.11]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.11
[0.1.10]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.10
[0.1.9]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.9
[0.1.8]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.8
[0.1.6]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.6
[0.1.5]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.5
[0.1.4]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.4
[0.1.3]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.3
[0.1.2]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.2
[0.1.1]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.1
[0.1.0]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.0
