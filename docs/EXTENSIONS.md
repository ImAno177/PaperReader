# Extension SDK

Status: implemented host contract. PaperReader extensions are separate Android applications. They
communicate with the host through versioned `dev.paperreader:extension-api` AIDL and run under a
different Linux UID. The host never loads third-party code into its own process.

## Scope and repositories

PaperReader supports two extension kinds:

| Kind | Supplies | Host-owned boundary |
| --- | --- | --- |
| Source | Search, exact lookup, metadata, and paper manifestations | Signed APK, bounded Binder calls, and Android `PackageInstaller` confirmation |
| Theme | Declarative palettes, typography, shapes, decorations, and semantic icons | Validation and host-owned rendering |

The host keeps provider implementations outside the app repository. The current repositories are:

- [PaperReader-sources](https://github.com/ImAno177/PaperReader-sources), the official Semantic
  Scholar, Crossref, arXiv, and Europe PMC source APKs plus the signed registry.
- [PaperReader source extension sample](https://github.com/ImAno177/PaperReader-source-sample), an
  exact-DOI Crossref example.
- [PaperReader theme extension sample](https://github.com/ImAno177/PaperReader-theme-sample), a
  declarative light/dark theme and complete semantic icon set.

## Build against the local API

The SDK is not published to Maven yet. Extension repositories use a composite build and substitute
`dev.paperreader:extension-api:0.1.0` with the local `:extension-api` project.

Place a PaperReader checkout in a directory named `PaperReader` under the extension repository, or
pass its path from an extension repository:

```powershell
.\gradlew.bat :app:assembleDebug
```

For a checkout at another location, set `PAPERREADER_SDK_PATH` to its absolute path:

```powershell
.\gradlew.bat :app:assembleDebug `
  -PpaperReaderSdkPath=$env:PAPERREADER_SDK_PATH
```

The current contract requires Android 9 (API 28) or newer and JVM target 17. The official source
repository uses JDK 21 and Android SDK Platform 36 with Build-Tools 36.1.0 in CI.

## Source extensions

A source APK exports one service for
`dev.paperreader.extensions.api.action.PAPER_SOURCE`. Its manifest metadata declares API version 1
and kind `source`. The descriptor contains:

- a stable provider ID and display name;
- a minimum request interval;
- capabilities such as `search`, `details`, and `pdf_link`;
- roles such as `search_engine`, `content_source`, and `metadata_engine`;
- supported exact identifiers: DOI, arXiv, PMID, and PMCID;
- supported search sorts: relevance, newest, and oldest.

Requests are asynchronous, bounded, and cancellable. Responses use neutral extension records rather
than host database or domain objects. A source preserves provider record IDs and provenance, validates
URLs, limits responses to 50 records per page, and reports rate limiting through `retryAfterMillis`.

Official provider roles are intentionally separate:

| Provider | Role | Routing rule |
| --- | --- | --- |
| Semantic Scholar | Search engine | Preferred free-text discovery and citation observations |
| Crossref | Metadata engine | Exact normalized DOI enrichment only |
| arXiv | Content source | Phrase-aware discovery, exact ID/version lookup, and manifestations |
| Europe PMC | Content source | Biomedical discovery, identifier lookup, and licensed manifestations |

The complete provider routing policy lives in [`SPEC.md`](SPEC.md). This file describes the extension
wire, packaging, and trust boundary.

## Theme extensions

A theme APK exports one service for
`dev.paperreader.extensions.api.action.PAPER_THEME`. Its manifest metadata declares API version 1
and kind `theme`.

A theme supplies complete declarative data:

- light and dark semantic color palettes;
- title, body, and label font families;
- corner, border, shadow, and decoration tokens;
- every `ThemeSemanticIcon` as bounded ASCII path data.

The current sample uses a `2400 x 2400` icon viewport and a 64 KiB per-icon limit. The host parses and
renders the paths. An extension cannot inject Compose code, layouts, arbitrary resources, JavaScript,
or host file paths. Missing, oversized, or malformed icons reject the whole theme.

## Local host development

The host can describe a local source or theme to a debug build through Gradle properties. Source
properties use the `paperReaderDevSource*` prefix, including package, service, provider ID, display
name, signer SHA-256, version code, minimum request interval, capabilities, roles, identifier types,
and supported sorts. Theme properties use the `paperReaderDevTheme*` prefix, including package,
service, display name, theme ID, signer SHA-256, and version code.

All signer values must be 64 hexadecimal characters. Do not commit keys, passwords, API credentials,
or private signing material. A missing or invalid signer configuration must fail closed.

## Trust and data boundary

Before binding, PaperReader verifies the exact package and exported service, version range, signing
certificate SHA-256, API metadata, extension kind, descriptor, and separate UID. Services also verify
the PaperReader package and signing certificate on every Binder entry point.

The host uses explicit Binder intents, bounded parcels and file descriptors, request timeouts,
cancellation propagation, and strict decoders. It never sends an extension its Room database,
private-storage root, arbitrary host path, global credential, or unrestricted intent.

The host never loads third-party DEX, JAR, or JavaScript. Source and theme packages cannot corrupt
the Library by failing inside their own process.

## Signed stores

The store envelope contains Base64-encoded exact UTF-8 index bytes and an Ed25519 signature over those
bytes:

```json
{
  "payload": "BASE64_OF_INDEX_BYTES",
  "signature": "BASE64_OF_64_BYTE_ED25519_SIGNATURE"
}
```

The decoded index uses schema version 1. Increment `sequence` whenever signed content changes. The
host rejects rollback, same-sequence equivocation, duplicate packages, unknown fields or values,
oversized data, non-HTTPS URLs, incompatible API ranges, and indexes beyond the clock-skew allowance.
The tracked [extension index fixture](examples/extension-index.json) documents the fields; the
runtime decoder remains authoritative.

`apkSha256` and `apkSizeBytes` are required together for every newly published installable release.
They bind the signed catalog to exact APK bytes. A legacy entry missing both values may remain visible
from a last-known-good catalog, but it is catalog-only and cannot be installed. `minimumVersionCode`
can revoke vulnerable older builds. Theme entries use `kind: theme` and `themeIds`; source-only
fields are not accepted on theme entries.

## Install and update lifecycle

The official store URL, store ID, and Ed25519 public key are pinned in the host. A user-managed store
requires an independent confirmation of its displayed public-key SHA-256 fingerprint.

The visible lifecycle states are:

- available;
- installed;
- update available;
- pending or downloading;
- awaiting Android confirmation;
- installing or installed;
- cancelled or failed;
- untrusted;
- orphaned, when an installed package is no longer in a trusted store.

On cold start, manual refresh, and constrained periodic work, the host refreshes each trusted store
independently. If one store fails, its last verified index remains available while the host compares
package, version, API, and signer information for the other stores. Updates are never installed
automatically.

After the user chooses `Install` or `Update`, the host downloads the APK into bounded app-private
staging, enforces the signed size while streaming, verifies SHA-256, and preflights package name,
version code, signer, service class, extension kind, and API compatibility. Source and theme APKs use
the same verified queue. Only then does the host create an Android `PackageInstaller` session.

Android presents the final consent surface. Pending downloads and open sessions can be cancelled.
Installer callbacks are accepted only for the exact active session. Package add, replace, and remove
broadcasts trigger a complete source and theme rescan, followed by trust reconciliation. The host
persists the session ID and expected version before commit and validates restored sessions after
process recreation.

## Sign an index

Keep the Ed25519 private key outside Git and sign the exact index bytes with the repository tool:

```bash
openssl genpkey -algorithm ED25519 -out extension-store-private.pem
python tools/sign_extension_index.py \
  --index extension-index.json \
  --private-key extension-store-private.pem \
  --output extension-index.signed.json
```

Publish the Base64 raw public key and its SHA-256 fingerprint through the trusted distribution path.
Store refresh keeps the last verified index when network, signature, schema, rollback, or equivocation
checks fail.

## Verification checklist

Before proposing an extension change:

1. Run the extension repository's unit tests, lint, and APK build locally.
2. Use recorded fixtures for provider parsing and routing. Live API calls are manual diagnostics, not
   deterministic test assertions.
3. Confirm cancellation, response-size limits, rate gating, caller verification, and separate-process
   behavior remain intact.
4. For a release, verify the APK signature, registry digest, exact byte size, API range, package, and
   service descriptor before publishing.

The host-side module boundary and release evidence are documented in
[`ARCHITECTURE.md`](ARCHITECTURE.md) and [`TESTING.md`](TESTING.md).
