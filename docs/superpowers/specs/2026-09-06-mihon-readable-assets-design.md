# Mihon-style readable assets design

Status: Approved for implementation on 2026-09-06. This design changes the current readable arXiv
artifact pipeline so HTML and figure assets are stored and served independently.

## Goal

Make image-heavy readable papers reliable without a figure-count limit. HTML remains a verified,
offline document, while each raster or SVG asset is fetched, validated, cached, and served as its
own bounded file, following the separation used by Mihon's page downloader and reader cache.

## Current root cause

`ArxivReadablePaperLoader` currently asks `ArxivHtmlSanitizer` to fetch every figure and embed each
successful response as a base64 data URI. `ReadablePaperCache` therefore stores one large HTML body,
and WebView receives that same large string. An image-count circuit breaker prevents process-memory
failure, but it also makes valid figures unavailable on papers with many or large assets.

## Architecture

The pipeline has two bounded lanes:

1. The document lane fetches the exact versioned arXiv HTML, sanitizes its structure, and records
   safe asset references. It does not fetch or embed image bytes.
2. The asset lane fetches those references with bounded concurrency, validates the response media
   type and bytes, sanitizes SVG bytes, and writes each successful asset atomically to an asset
   cache. There is no asset-count limit. Per-asset byte and SVG-complexity limits remain security
   controls, and the cache retains a global byte quota with LRU eviction.

The sanitized body stores opaque `paperreader-asset://<id>` references. The app renderer maps them to
an app-owned local HTTPS path. A network-blocked WebView intercepts only those local asset paths and
opens a validated cache stream; no remote URL or app-private filesystem path is exposed to the
document. `loading="lazy"` and `decoding="async"` remain on images to limit renderer-side decoding.

The loader materializes all safe asset references before returning a ready document. This keeps the
reader deterministic and makes an offline-retained document complete, while the bounded asset lane
prevents a burst of simultaneous connections. Failed or unsafe assets become explicit placeholders
and preserve their captions. A missing cache file invalidates the affected readable cache entry and
causes a normal online reload; retained/offline reads fail closed rather than using a mutable copy.

## Contracts and persistence

`ReadablePaperDocument` exposes only asset metadata: opaque ID, media type, SHA-256, byte length, and
the opaque asset-group key. It never exposes a storage path. `PaperReaderLogic` exposes a synchronous
asset-opening method that returns a stream for a validated document asset, allowing WebView's
`shouldInterceptRequest` callback to stay local and non-networking.

`ReadablePaperCache` stores the sanitized body, document metadata, and the asset metadata list. A
separate `ReadablePaperAssetCache` stores files under a fixed app-private root using safe hash-based
names, atomic temporary files, group offline markers, and integrity checks. Body and asset entries
are published in an order that permits startup cleanup of orphan asset files. Removing a paper,
reconciling artifacts, or evicting a body also removes or invalidates its asset group.

The cache contract version and renderer contract version advance together. Existing base64 artifacts
are not reused by the new reader path. The document hash remains the hash of the sanitized body and
stable asset references; asset hashes are verified independently when opened or retained.

## Export behavior

Normal reading uses local asset URLs. User-requested HTML export is the exception: it reads the
validated cached asset streams and inlines them as data URIs in the exported HTML. Export remains
self-contained and retains its existing provenance metadata and size guard. If a required asset
cannot be read, export fails explicitly instead of writing a broken local-path reference.

## Safety and failure behavior

- Only exact HTTPS arXiv document and same-directory asset URLs are accepted.
- Raster media types remain limited to PNG, JPEG, WebP, and GIF.
- SVG remains data-only, UTF-8 validated, free of executable/external references, and bounded by
  element complexity; the size budget is raised enough for known valid research figures without
  removing the safety checks.
- Asset requests use the separate bounded lane and remain cancellable with the parent load.
- Cache writes are atomic; incomplete files are never published as readable assets.
- Asset failures are reported as `FIGURE_UNAVAILABLE`; the former count-limit warning is retained
  only for compatibility with older cached/test behavior and is not emitted by the new path.
- No network is enabled in the reader WebView, and no JavaScript bridge is added.

## Verification

No test source is added or modified. Existing JVM, lint, build, and connected suites are run locally.
The local emulator audit covers Attention, CGP-Tuning, LoRA, Llama, ResNet, and InstructGPT; it
checks first load, cache reopen, offline reopen, figure/caption visibility, export, back navigation,
search, and process stability. Source-side asset counts are compared with app-side asset-cache
metadata. GitHub is used only after the local gate passes, for PR checks, review, merge, and release.
