# PaperReader arXiv plugin boundary implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the arXiv source extension own readable HTML acquisition and sanitization while the host owns only generic verification, cache publication, and a separate bounded asset lane.

**Architecture:** Extend the neutral readable-document metadata with a versioned provider contract, keep arXiv parsing/sanitization in `PaperReader-sources/source-arxiv`, and replace the host's arXiv-specific loader/fetcher with a provider-neutral coordinator. Body references remain opaque and assets remain independent files.

**Tech Stack:** Kotlin, Android AIDL, OkHttp, Jsoup only in the source extension and final host safety gate, coroutines, Room-independent app-private cache.

**Spec:** `docs/superpowers/specs/2026-09-07-paperreader-architecture-mvvm-plugin-ux-design.md`

## Global Constraints

- The host never loads source APK code or owns provider-specific parsing.
- The source extension runs in a separate package/UID and depends only on `:extension-api`.
- The host final gate remains deny-by-default and the WebView remains network-blocked.
- No image-count cap is introduced; per-asset bytes, SVG complexity, concurrency, retry, and cache quotas remain.
- Asset references never expose a filesystem path or remote URL to the WebView.
- Cancellation, request correlation, response bounds, hashes, signer/caller checks, and provenance remain fail-closed.
- Run source/host tests and builds locally; do not add a GitHub functional/benchmark runner.

## File map

- Modify `extension-api/src/main/kotlin/dev/paperreader/extensions/api/SourceReadableContract.kt` and readable bundle keys for the backward-compatible contract version.
- Modify `PaperReader-sources/source-arxiv/src/main/kotlin/.../ArxivService.kt` and `ArxivReadableDocumentSanitizer.kt` for provider-owned metadata and sanitization.
- Create/modify neutral host reader classes under `logic/src/main/kotlin/dev/paperreader/logic/reader` for generic asset validation and plugin loading.
- Modify `logic/src/main/kotlin/dev/paperreader/logic/PaperReaderLogic.kt` to inject the neutral loader.
- Delete provider-specific host classes after `rg` proves no valid caller remains: `ArxivReadablePaperLoader`, `ArxivHtmlSanitizer`, and `ArxivReadableResourceFetcher`.
- Update source fixtures/tests only at the nearest existing fixture seam when the wire contract needs a deterministic assertion.

## Task 1: Version the readable-document metadata

**Files:**

- Modify: `extension-api/src/main/kotlin/dev/paperreader/extensions/api/SourceReadableContract.kt`
- Modify: `extension-api/src/main/kotlin/dev/paperreader/extensions/api/ExtensionContract.kt` for
  the readable bundle key
- Modify: existing extension-api contract tests only if the new field lacks coverage

**Interfaces:**

- Add `contractVersion: String` to `SourceReadableDocumentMetadata` with a bounded ASCII/wire-safe format.
- `fromBundle` accepts an absent field as the legacy value; `toBundle` always writes the explicit value.
- Host cache identity consumes this value after verification.

- [ ] **Step 1: Define the exact value validation.**

  Use a bounded pattern such as `[a-z0-9][a-z0-9._-]{0,63}` and reject blank/oversized values. Keep
  the legacy decoder value a named constant rather than a literal in multiple files.

- [ ] **Step 2: Add bundle serialization and backward-compatible decoding.**

  ```kotlin
  data class SourceReadableDocumentMetadata(
      ...,
      val contractVersion: String = LEGACY_READABLE_CONTRACT_VERSION,
  )
  ```

  `toBundle` writes the field; `fromBundle` calls `bundle.getString(key) ?: LEGACY_READABLE_CONTRACT_VERSION`.

- [ ] **Step 3: Run extension-api local checks.**

  ```powershell
  .\gradlew.bat :extension-api:testDebugUnitTest :extension-api:lintDebug
  ```

- [ ] **Step 4: Commit the contract change.**

  ```powershell
  git add extension-api
  git commit -m "feat(extension-api): version readable document contracts"
  ```

## Task 2: Publish provider-owned arXiv document metadata

**Files:**

- Modify: `source-arxiv/src/main/kotlin/dev/paperreader/extensions/sources/arxiv/ArxivService.kt`
- Modify: `source-arxiv/src/main/kotlin/dev/paperreader/extensions/sources/arxiv/ArxivReadableDocumentSanitizer.kt`
- Modify: existing `source-arxiv` fixtures/tests nearest to readable-document assertions

**Interfaces:**

- `ArxivService.getReadableDocumentSource` fetches HTML and passes it only to `ArxivReadableDocumentSanitizer`.
- The returned `SourceReadableDocumentMetadata` includes the source-owned contract version, source hash, document hash, sections, warnings, and opaque assets.

- [ ] **Step 1: Name the source contract constant.**

  Keep it next to provider sanitizer policy, increment it when provider-owned normalization changes,
  and pass it into `SourceReadableDocumentMetadata(contractVersion = ...)`.

- [ ] **Step 2: Verify no asset bytes are embedded in the body.**

  The sanitizer output must use `paperreader-asset://<64 lowercase hex>` references and return each
  unique source URL as metadata. It must preserve captions and warnings for unsafe/unsupported figures.

- [ ] **Step 3: Extend the existing source fixture assertion.**

  Assert the contract version, source/document hashes, opaque body reference format, and that the
  body contains no executable markup. Do not create a second test file for the same parser.

- [ ] **Step 4: Run source tests/lint/build locally.**

  ```powershell
  .\gradlew.bat :source-arxiv:testDebugUnitTest :source-common:testDebugUnitTest :source-arxiv:lintDebug :source-arxiv:assembleDebug
  ```

- [ ] **Step 5: Commit the provider change.**

  ```powershell
  git add source-arxiv
  git commit -m "feat(arxiv): publish versioned sanitized readable documents"
  ```

## Task 3: Extract generic host asset validation

**Files:**

- Create/modify: `logic/src/main/kotlin/dev/paperreader/logic/reader/ReadableAssetSanitizer.kt`
- Modify: `ReadablePaperContract.kt` and `ReadablePaperAssetCache.kt` only where neutral types are required
- Modify: existing asset-cache tests nearest to validation behavior

**Interfaces:**

- `ReadableAssetSanitizer.sanitize(resource: ReadableRemoteResource): ReadableRemoteResource?` validates raster media types, UTF-8 self-contained SVG, executable markup, and complexity.
- `ReadableAssetSanitizer.replaceUnavailableAssetReferences(bodyHtml: String, unavailableIds: Set<String>): String` preserves captions.
- No function in this class constructs or checks `arxiv.org` URLs.

- [ ] **Step 1: Move reusable validation from `ArxivReadableFigureProcessor`.**

  Preserve the current safe media types, maximum single asset bytes, SVG element bound, data-image
  bound, and unavailable-asset fallback semantics. Keep the legacy inline compatibility API only if an existing
  caller still requires it; otherwise delete the dead inline path with the old loader.

- [ ] **Step 2: Add generic trusted-directory URL validation.**

  Validate HTTPS, no user info/fragment/unsupported port, and the existing trusted same-directory
  policy relative to the verified document source. Reject a body asset whose metadata URL and body
  reference do not match.

- [ ] **Step 3: Run focused local logic tests.**

  ```powershell
  .\gradlew.bat :logic:testDebugUnitTest --tests "*Readable*" --no-configuration-cache
  ```

- [ ] **Step 4: Commit the neutral asset validator.**

  ```powershell
  git add logic/src/main/kotlin/dev/paperreader/logic/reader
  git commit -m "refactor(logic): make readable asset validation provider-neutral"
  ```

## Task 4: Replace the host plugin loader and wire cache identity

**Files:**

- Create/modify: `logic/src/main/kotlin/dev/paperreader/logic/reader/PluginReadablePaperLoader.kt`
- Modify: `logic/src/main/kotlin/dev/paperreader/logic/reader/ReadablePaperCache.kt`
- Modify: `logic/src/main/kotlin/dev/paperreader/logic/PaperReaderLogic.kt`
- Delete after reference scan: `logic/src/main/kotlin/dev/paperreader/logic/network/ArxivReadableResourceFetcher.kt`
- Delete after reference scan: `logic/src/main/kotlin/dev/paperreader/logic/reader/ArxivReadablePaperLoader.kt`
- Delete after reference scan: `logic/src/main/kotlin/dev/paperreader/logic/reader/ArxivHtmlSanitizer.kt`

**Interfaces:**

- `PluginReadablePaperLoader` accepts `(providerId: String) -> SourceExtensionTransport?`, a generic document/asset fetcher, and the existing cache.
- The cache key includes provider ID, provider record ID, version, readable contract version, and renderer contract version.
- `removeArtifacts`, `reconcileArtifacts`, and `openAsset` remain available through neutral loader methods used by `PaperReaderLogic`.

- [ ] **Step 1: Separate document and asset lanes.**

  The document lane calls the Binder transport and never downloads asset bytes. After metadata/body
  verification, `coroutineScope` launches bounded asset fetches under an asset semaphore independent
  of the document request gate. Cancellation of the document load cancels all children.

- [ ] **Step 2: Remove arXiv constants from the host loader.**

  Do not retain `ARXIV_PROVIDER_ID`, `ARXIV_HTML_PREFIX`, arXiv ID regexes, or arXiv URL construction
  in the new coordinator. The manifestation's provider ID/record ID/version become the request
  inputs; the extension returns the authoritative source URL.

- [ ] **Step 3: Verify the neutral response envelope.**

  Check request ID, exact source version, body size, UTF-8, `sha256(body)`, contract version, safe
  source URL, section bounds, warning enum values, unique asset IDs, and trusted-directory asset URLs before
  cache publication. Any failure returns `ReadablePaperFailure.INVALID_RESPONSE`.

- [ ] **Step 4: Preserve cache/offline/export semantics.**

  Keep atomic body publication, independent asset metadata/files, retained offline markers, startup
  reconciliation, exact hashes, caption-preserving warnings, and the existing export path. A retained
  document must never refetch a different source revision.

- [ ] **Step 5: Remove obsolete provider-specific host classes.**

  Run `rg -n "ArxivReadablePaperLoader|ArxivHtmlSanitizer|ArxivReadableResourceFetcher|ArxivReadableFigureProcessor" logic app`.
  Delete only classes with no valid references; keep neutralized generic code under neutral names.

- [ ] **Step 6: Wire `PaperReaderLogic.open`.**

  Construct the generic loader with the existing host `OkHttpClient` only for asset requests. Do not
  pass a document fetcher for arXiv; HTML acquisition must go through `sourceExtensionCoordinator`.

- [ ] **Step 7: Run host/source gates.**

  ```powershell
  .\gradlew.bat hostUnitTest hostLint :app:assembleDebug --no-configuration-cache
  .\gradlew.bat :source-arxiv:testDebugUnitTest :source-arxiv:assembleDebug
  ```

- [ ] **Step 8: Commit the host boundary.**

  ```powershell
  git add logic extension-api
  git commit -m "refactor(reader): move arxiv html ownership into plugin"
  ```

## Task 5: Plugin review gate

- [ ] **Step 1:** Generate host and source diff review packages from the branch base.
- [ ] **Step 2:** Review contract compatibility, cancellation, URL validation, cache identity, and no-count-cap behavior.
- [ ] **Step 3:** Fix reviewed findings through the implementer/re-review loop.
- [ ] **Step 4:** Record exact local commands, fixture evidence, and known deferred behavior in the SDD ledger.
