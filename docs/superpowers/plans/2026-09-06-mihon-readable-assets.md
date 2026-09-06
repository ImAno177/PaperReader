# Mihon-style readable assets implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace base64-packed readable-paper figures with independently cached, verified asset files so image-heavy papers render and reopen offline without a figure-count limit.

**Architecture:** Keep document acquisition and asset acquisition as separate bounded lanes. The logic layer sanitizes HTML into opaque asset references, writes verified asset files and metadata, and exposes only validated streams. The app maps those references to local WebView URLs and materializes them only for user-requested HTML export.

**Tech Stack:** Kotlin, coroutines, OkHttp, Jsoup, Java NIO, Android WebView, Room-backed existing task/library boundaries, Gradle wrapper, local API 36 emulator.

**Spec:** `docs/superpowers/specs/2026-09-06-mihon-readable-assets-design.md`

## Global Constraints

- Do not add or modify test source files; run the existing local suites and manual emulator audit.
- Do not expose app-private filesystem paths, remote figure URLs, or a JavaScript bridge to WebView.
- Do not impose an image-count limit; keep only per-asset validation and byte/quota limits.
- Preserve provenance, offline retention, annotations, search, table-of-contents, citations, and HTML export.
- Use atomic cache publication, cancellation-aware coroutines, and the existing app-to-logic boundary.
- Run local host/connected verification before push, PR, merge, or release.
- Keep production Kotlin files below 600 lines and update `docs/SPEC.md`, `docs/ARCHITECTURE.md`, `docs/TESTING.md`, and `CHANGELOG.md` for the shipped behavior.

---

### Task 1: Record the approved design and baseline

**Files:**

- Create: `docs/superpowers/specs/2026-09-06-mihon-readable-assets-design.md`
- Create: `docs/superpowers/plans/2026-09-06-mihon-readable-assets.md`
- Read: `docs/SPEC.md`, `docs/ARCHITECTURE.md`, `docs/TESTING.md`

**Interfaces:**

- Consumes: the approved Mihon-style architecture and the current clean feature branch.
- Produces: committed design/plan documents and a clean baseline for implementation.

- [ ] **Step 1: Check the feature branch and baseline**

  Run `git status --short --branch` and `git log -1 --oneline`. The branch must be
  `feat/mihon-readable-assets` with no unrelated modifications.

- [ ] **Step 2: Commit the approved design**

  Run `git add docs/superpowers/specs/2026-09-06-mihon-readable-assets-design.md docs/superpowers/plans/2026-09-06-mihon-readable-assets.md` followed by
  `git commit -m "docs(reader): design mihon-style asset caching"`.

- [ ] **Step 3: Reconfirm the existing local gate**

  Run `./gradlew.bat hostUnitTest hostLint :app:assembleDebug --no-daemon --no-configuration-cache --console=plain` and record the exit code before production edits.

### Task 2: Add file-backed asset contracts and cache

**Files:**

- Modify: `logic/src/main/kotlin/dev/paperreader/logic/reader/ReadablePaperContract.kt`
- Modify: `logic/src/main/kotlin/dev/paperreader/logic/reader/ReadablePaperCache.kt`
- Create: `logic/src/main/kotlin/dev/paperreader/logic/reader/ReadablePaperAssetCache.kt`
- Read: `logic/src/main/kotlin/dev/paperreader/logic/reader/ArxivReadablePaperLoader.kt`

**Interfaces:**

- Consumes: existing readable cache keys, document metadata, and offline-retention contract.
- Produces: `ReadablePaperAsset`, `ReadablePaperAssetContent`, cached asset metadata, atomic asset write/open/retain/remove operations.

- [ ] **Step 1: Extend the document contract compatibly**

  Add defaulted asset metadata to `ReadablePaperDocument` and `CachedReadablePaper` so existing callers without figures still compile. Validate asset IDs as lowercase hashes, media types against the safe image set, SHA-256 as 64 lowercase hex characters, and byte lengths as positive bounded values.

- [ ] **Step 2: Implement the asset cache**

  Store each asset beneath a fixed cache root using `<assetGroupKey>/<assetId>.asset` and temporary files. Implement `write`, `open`, `readBytes`, `allPresent`, `keepForOffline`, `removeGroup`, and `removeGroupsNotIn`. Validate file size and SHA-256 before returning a stream; reject path traversal and malformed keys.

- [ ] **Step 3: Include asset metadata in body manifests**

  Extend the existing manifest with encoded asset-group and asset records while accepting old manifests with an empty asset list. Keep body hash validation and existing LRU behavior intact for legacy body entries.

- [ ] **Step 4: Run the nearest existing logic tests**

  Run `./gradlew.bat :logic:testDebugUnitTest --tests dev.paperreader.logic.reader.ReadablePaperCacheTest --tests dev.paperreader.logic.reader.ArxivReadablePaperTest --no-daemon --no-configuration-cache --console=plain`. Expected: PASS without changing test files.

### Task 3: Split sanitization from asset materialization

**Files:**

- Modify: `logic/src/main/kotlin/dev/paperreader/logic/reader/ArxivHtmlSanitizer.kt`
- Modify: `logic/src/main/kotlin/dev/paperreader/logic/reader/ArxivReadablePaperLoader.kt`
- Modify: `logic/src/main/kotlin/dev/paperreader/logic/network/ArxivReadableResourceFetcher.kt`
- Modify: `logic/src/main/kotlin/dev/paperreader/logic/reader/ReadablePaperContract.kt`

**Interfaces:**

- Consumes: exact arXiv HTML and safe same-directory figure references.
- Produces: structure-only sanitized HTML, deterministic asset references, bounded parallel asset materialization, explicit placeholders for failed assets, and no production figure-count warning.

- [ ] **Step 1: Add reference-mode sanitization**

  Reuse the existing structural cleanup, author/table normalization, link policy, and executable-markup checks. Replace valid image/object sources with `paperreader-asset://<assetId>` and return deduplicated source URL metadata. Keep the existing `sanitize` behavior as a compatibility path for unchanged tests and callers.

- [ ] **Step 2: Validate and materialize each asset**

  Add a sanitizer entry point that accepts one fetched `ReadableRemoteResource`, retains safe raster bytes, or returns strictly sanitized SVG bytes. Raise the known valid large-figure byte budget enough for file-backed storage while preserving host/path, media-type, UTF-8, executable-markup, external-reference, and complexity checks.

- [ ] **Step 3: Fetch assets in bounded batches**

  In `ArxivReadablePaperLoader`, fetch reference batches with the existing asset lane and write each successful validated asset to `ReadablePaperAssetCache` before starting the next batch. Replace failed references with the existing caption-preserving placeholder. Do not use a figure-count condition; only per-asset validation and cache write failures produce unavailable figures.

- [ ] **Step 4: Publish a complete document record**

  Compute the document hash from the final reference/placeholder body, write the asset metadata in the body cache manifest, and return a document whose asset list points only to successfully published files. On cache hit, require body and every listed asset to validate; on retained/offline load, fail closed if any asset is missing.

- [ ] **Step 5: Run logic verification**

  Run `./gradlew.bat :logic:testDebugUnitTest :logic:lintDebug --no-daemon --no-configuration-cache --console=plain`. Expected: PASS with no test-source diff.

### Task 4: Serve local assets in the reader and preserve export

**Files:**

- Modify: `logic/src/main/kotlin/dev/paperreader/logic/PaperReaderLogic.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/reader/ReadablePaperWebViewConfiguration.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/reader/ReadablePaperActivity.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/reader/ReadablePaperRendering.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/reader/ReadablePaperHtmlExport.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/screen/ReadableHtmlDownloadAction.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/PaperReaderViewModel.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/PaperReaderApp.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/screen/ManifestationCard.kt`

**Interfaces:**

- Consumes: document asset metadata and logic-owned validated streams.
- Produces: local-only WebView asset responses and self-contained export HTML.

- [ ] **Step 1: Expose only a validated asset stream**

  Add a `PaperReaderLogic.openReadablePaperAsset(document, assetId)` method that verifies the current document's asset metadata before delegating to the asset cache. Return media type and stream, never a filesystem path.

- [ ] **Step 2: Rewrite reader asset references**

  Map only safe `paperreader-asset://<assetId>` values to the fixed local renderer host/path. Keep CSP image sources restricted to `data:` and the app-owned local host, and keep all remote HTTP(S) requests blocked.

- [ ] **Step 3: Intercept local assets**

  Extend `configureReadablePaperWebView` with a local-asset callback. In `ReadablePaperActivity`, parse only the bounded asset ID, verify it belongs to `currentDocument`, and return a `WebResourceResponse` backed by the validated stream. Missing local assets return an empty safe response and never fall through to network.

- [ ] **Step 4: Inline assets only for export**

  Thread a suspendable asset-read callback through the existing UI flow. Read each listed asset from the logic cache on `Dispatchers.IO`, create safe data URIs using the recorded media type, and fail explicitly if any required asset is unavailable. Keep existing provenance and filename behavior.

- [ ] **Step 5: Run app verification**

  Run `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --no-configuration-cache --console=plain`. Expected: PASS with no test-source diff.

### Task 5: Update product/architecture documentation and changelog

**Files:**

- Modify: `docs/SPEC.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/TESTING.md`
- Modify: `CHANGELOG.md`

**Interfaces:**

- Consumes: the implemented file-backed asset contract and local WebView behavior.
- Produces: accurate shipped behavior and local verification instructions.

- [ ] **Step 1: Replace base64-only claims**

  Document that readable HTML stores opaque local asset references, assets are independently cached and integrity-checked, and only export inlines data URIs. State that no image-count limit exists; byte, SVG safety, and cache quotas remain.

- [ ] **Step 2: Document the two lanes and offline group**

  Update architecture and testing documents with document/asset lane behavior, cache-group cleanup, and the exact emulator audit required for image-heavy papers.

- [ ] **Step 3: Add the user-visible changelog entry**

  Add an Unreleased Changed/Fixed entry for file-backed readable assets, removal of the figure-count circuit breaker, and reliable offline figure serving.

- [ ] **Step 4: Run Markdown checks**

  Run the repository Markdown lint/link command from `.github/workflows/android-ci.yml`; require exit code 0 and no generated files staged.

### Task 6: Full local build and emulator audit

**Files:**

- Read: `docs/TESTING.md`
- Artifact: `app/build/outputs/apk/debug/app-debug.apk`
- Local-only evidence: `%TEMP%/paperreader-mihon-assets-*`, emulator cache/log output

**Interfaces:**

- Consumes: the feature branch and existing emulator `emulator-5554` (`covaigay_api36(AVD) - 16`, API 36).
- Produces: fresh local evidence for all host gates, cache behavior, offline behavior, and UI stability.

- [ ] **Step 1: Run the complete host gate**

  Run `./gradlew.bat hostUnitTest hostLint :app:assembleDebug :app:assembleRelease --no-daemon --no-configuration-cache --console=plain --stacktrace`. Require `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run the existing connected suites**

  Run the exact connected commands in `docs/TESTING.md` with `-PpaperReaderConnectedTestApplicationIdSuffix=.uitest`. Do not add or modify tests. Require zero failures/errors/skips.

- [ ] **Step 3: Install the freshly built debug APK**

  Install on `emulator-5554` using ADB only after the local host gate passes. Do not wipe the emulator or delete unrelated app data.

- [ ] **Step 4: Audit first-load and cache-reopen behavior**

  Open Attention, CGP-Tuning, LoRA, Llama, ResNet, and InstructGPT through the installed arXiv extension. For each, check reader remains alive, figures/captions appear, toolbar/back/search/contents remain usable, asset files exist separately from the HTML body, and no figure-count warning is emitted.

- [ ] **Step 5: Audit offline and export behavior**

  Reopen cached Attention and Llama with airplane mode enabled, verify body and figures remain available, export one paper to HTML and inspect that it contains self-contained data URIs rather than app-local paths, then restore network state.

- [ ] **Step 6: Capture exact evidence**

  Save only local logs/counts outside the repository: source asset count, cached asset count/bytes, body size, warnings, process stability, and exact device/API. Do not claim physical pinch or any manual action not actually performed.

### Task 7: Review, merge, and release

**Files:**

- Read: full branch diff, PR checks, release workflow output
- Modify only if review requires: implementation/docs files above

**Interfaces:**

- Consumes: green local gates and clean feature branch.
- Produces: reviewed PR, merged main, and a new signed 0.1.x GitHub release.

- [ ] **Step 1: Run final verification before commit**

  Run `git diff --check`, `git status --short`, and the complete local gate from Task 6. Confirm no test source file changed.

- [ ] **Step 2: Commit and push through the PR workflow**

  Use a conventional commit on `feat/mihon-readable-assets`, push it, and create one PR with `gh pr create` using the `create-pr` skill. Describe the root cause, file-backed asset design, and why limits are byte/security quotas rather than image count.

- [ ] **Step 3: Review the PR and merge only green checks**

  Inspect the complete diff and required GitHub checks. Apply `receiving-code-review` to each finding, fix valid issues locally, rerun the full local gate, push updates, and merge only after required checks are green.

- [ ] **Step 4: Prepare the next release**

  Move the Unreleased entries to the next dated 0.1.x version with a monotonically increasing version code. Keep `Unreleased` present and empty for future work.

- [ ] **Step 5: Publish and verify the release**

  Dispatch the existing release workflow only after merged-main CI is green. Verify tag, target commit, signed APK version/signature, SBOM asset, and release page. Report the exact APK hash and any artifact not published.
