# PaperReader

PaperReader is a local-first Android app for discovering, saving, reading, and tracking scientific
papers. It uses real arXiv and Crossref data, keeps the library and reading state on-device, and
prefers a mobile-readable document over a fixed two-column PDF whenever a verified structured source
is available.

The current vertical slice supports Android 9 (API 28) and newer. For an exact-version arXiv paper it
loads official arXiv HTML through the logic layer, sanitizes and integrity-checks it, embeds bounded
same-version figures, and renders a one-column offline document with MathML, tables, search, table of
contents, selectable text, 85–200% text sizing, themes, and progress. The immutable original PDF
remains available as the fidelity fallback.

> Status: active pre-1.0 development. Official arXiv HTML and Original PDF reading are implemented.
> General PDF-to-reflow extraction, OCR, annotations, and community-provider execution are not yet
> production features.

## What works

- Live federated arXiv and Crossref search with exact DOI/arXiv handling and isolated provider errors.
- Room-backed library, reading status, collections, history, bookmarks, saved searches, and tasks.
- Verified cancellable PDF downloads with app-private storage and an in-app AndroidX PDF reader.
- Mobile arXiv reader using exact manifestation versions, typed fallback reasons, offline cache,
  provenance/license disclosure, native TOC/search, responsive figures/tables/math, and Original PDF
  fallback.
- Manual metadata backup/restore with preview and transactional merge; PDFs and credentials are not
  included.
- English-only UI with Doodle, Retro, and Neobrutalism presets in light and dark mode.
- Optional, disabled-by-default daily saved-search refresh through WorkManager.

## Reader security model

Remote HTML is never displayed directly. `:logic` performs the bounded fetch and jsoup safelist
normalization, allows raster figures only from the exact arXiv document path, and atomically caches a
SHA-256-verified artifact. `:app` renders only that fragment in a non-exported WebView with JavaScript,
file/content access, storage, mixed content, and network loading disabled under a deny-by-default CSP.
Raw TeX is not compiled in the app process.

See [SPEC.md](SPEC.md), [ARCHITECTURE.md](ARCHITECTURE.md), and
[docs/MIHON_ARCHITECTURE.md](docs/MIHON_ARCHITECTURE.md) for the product model, module boundary, and
the Mihon patterns that were kept, adapted, or rejected.

## Build

Requirements:

- Android Studio JBR 21 or another compatible JDK 21
- Android SDK Platform 36.1 and Build Tools 36.1.0
- Android SDK Platform 36 for `:logic`

On Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug
```

On macOS/Linux, configure `JAVA_HOME` and `ANDROID_HOME`, then run:

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the full
unit/lint gate and publishes debug and unsigned release APK artifacts for each push to `main` and for
pull requests.

## Verify

```powershell
.\gradlew.bat :logic:testDebugUnitTest :logic:lintDebug `
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

With an emulator connected:

```powershell
.\gradlew.bat :logic:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Unit/provider tests use deterministic fixtures rather than live network calls. Manual runtime audits
use real provider data separately.

## Privacy

PaperReader has no analytics, advertising SDK, cloud parser, or account requirement. Searches and
paper downloads contact the selected public provider; external links open only after a user action.
Library state, files, caches, progress, and backup previews stay in app-private/on-device storage
unless the user explicitly exports a metadata backup.

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md) before proposing changes. Please report vulnerabilities using
GitHub's private vulnerability reporting flow described in [SECURITY.md](SECURITY.md), not a public
issue.

Licensed under the [Apache License 2.0](LICENSE). Third-party attributions are listed in
[NOTICE](NOTICE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
