# Changelog

This file records user-visible changes to PaperReader. The `Unreleased` section describes the
current branch. Released sections correspond to Git tags.

## [Unreleased]

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

[Unreleased]: https://github.com/ImAno177/PaperReader/compare/v0.1.5...HEAD
[0.1.5]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.5
[0.1.4]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.4
[0.1.3]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.3
[0.1.2]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.2
[0.1.1]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.1
[0.1.0]: https://github.com/ImAno177/PaperReader/releases/tag/v0.1.0
