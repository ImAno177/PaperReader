# Extension SDK

Status: implemented host contract. PaperReader extensions are separate Android applications. They
communicate with the host through the versioned `dev.paperreader:extension-api` AIDL contract and run
under a different Linux UID. The host never loads third-party code into its process.

## Current extension types

| Type | Current role | Install boundary |
| --- | --- | --- |
| Source | Search, exact lookup, metadata, and paper manifestations | Signed APK, bounded Binder calls, Android PackageInstaller confirmation |
| Theme | Declarative palettes, typography, shapes, decorations, and semantic icons | Signed APK, validated descriptor and icon paths, host-owned rendering |

The official source repository currently publishes Semantic Scholar, Crossref, arXiv, and Europe PMC
packages. The host keeps provider implementations outside this repository.

Reference repositories:

- [Official source extensions](https://github.com/ImAno177/PaperReader-sources) - independently
  released Semantic Scholar, Crossref, arXiv, and Europe PMC APKs plus the signed official registry.
- [Theme extension sample](https://github.com/ImAno177/PaperReader-theme-sample) - a complete
  declarative light/dark theme and semantic icon set.

## Build against the SDK

Until the SDK is published to Maven, use a composite build. Clone PaperReader into the extension
repository as `PaperReader`, or pass its path:

```bash
./gradlew :app:assembleDebug -PpaperReaderSdkPath=/path/to/PaperReader
```

The extension build substitutes `dev.paperreader:extension-api:0.1.0` with the local
`:extension-api` project. The current API requires Android 9 or newer and JVM target 17.

## Source extensions

A source APK exports exactly one service for
`dev.paperreader.extensions.api.action.PAPER_SOURCE`. Its manifest metadata declares API version `1`
and kind `source`. The descriptor declares:

- stable provider ID and display name;
- minimum request interval;
- capabilities (`search`, `details`);
- roles (`search_engine`, `content_source`, `metadata_engine`);
- accepted exact identifier types (`doi`, `arxiv`, `pmid`, `pmcid`);
- supported search sorts.

Search/detail requests are asynchronous, bounded, and cancellable. Responses use neutral extension
records instead of host database/domain types. A source preserves provider record IDs and provenance,
validates URLs, limits responses to 50 records per page, and reports rate limiting through
`retryAfterMillis`.

Each official provider owns one upstream API only. The canonical provider roles and host routing
policy live in the [`Provider model`](SPEC.md#provider-model); this document owns only the extension
wire, packaging, and trust contract.

## Theme extensions

A theme APK exports exactly one service for
`dev.paperreader.extensions.api.action.PAPER_THEME`. Its manifest metadata declares API version `1`
and kind `theme`.

A theme is complete declarative data:

- light and dark semantic color palettes;
- title, body, and label font families;
- corner, border, shadow, and decoration tokens;
- every `ThemeSemanticIcon` as bounded ASCII path data.

Icon paths use a `2400 × 2400` viewport and are limited to 64 KiB each. The host parses and renders
them. Extensions cannot inject Compose code, layouts, arbitrary resources, JavaScript, or file paths.
Missing, oversized, or malformed icons reject the whole theme.

## Runtime trust

Before binding, PaperReader verifies the exact package and exported service, version range,
certificate SHA-256, API metadata, extension kind, descriptor, and separate UID. Services also verify
the PaperReader package and signing certificate on every Binder entry point.

The host uses explicit Binder intents, bounded parcels/file descriptors, request timeouts,
cancellation propagation, and strict decoders. It never sends an extension its Room database,
private-storage root, arbitrary host path, global credential, or unrestricted intent.

For local testing, provide the `paperReaderDevSource*` or `paperReaderDevTheme*` Gradle properties in
`app/build.gradle.kts`. Do not commit keys, passwords, API credentials, or private-key material.

## Signed stores

The envelope contains Base64 exact UTF-8 index bytes and an Ed25519 signature over those bytes:

```json
{
  "payload": "BASE64_OF_INDEX_BYTES",
  "signature": "BASE64_OF_64_BYTE_ED25519_SIGNATURE"
}
```

The decoded index uses schema version 1. Increment `sequence` whenever any signed content changes.
PaperReader rejects rollback, same-sequence equivocation, duplicate packages, unknown fields/values,
oversized data, non-HTTPS URLs, incompatible API ranges, and indexes beyond the clock-skew allowance.
The complete tracked example in [`docs/examples/extension-index.json`](examples/extension-index.json)
is the canonical documentation fixture for index fields; the runtime decoder remains authoritative.

`apkSha256` and `apkSizeBytes` are required together for every newly published installable release. They
bind the signed registry to exact APK bytes. A legacy schema-v1 entry missing both fields remains visible
from a last-known-good catalog but is catalog-only: the host must not offer or perform installation.
`minimumVersionCode` revokes vulnerable older builds. Theme entries use `"kind": "theme"` and
`"themeIds"`; source-only fields are forbidden.

## Install and update lifecycle

The official store URL, store ID, and Ed25519 public key are pinned in the host. User-managed stores
require the user to verify a displayed public-key SHA-256 fingerprint through an independent channel.

PaperReader uses these states:

- available;
- installed;
- update available;
- pending/downloading;
- awaiting Android confirmation;
- installing/installed;
- cancelled/failed;
- untrusted;
- orphaned (installed package no longer in any trusted store).

On cold start, manual refresh, and constrained periodic work, the host independently refreshes every
trusted store, preserves each last verified index when one store fails, and compares
package/version/API/signer information. It may notify about compatible source or theme updates, but
never installs one automatically.

After the user chooses Install or Update, the host downloads into a bounded app-private cache,
enforces the signed size during streaming, verifies SHA-256, and preflights package name, versionCode,
signer, service class, kind, and API compatibility. Source and theme APKs use this same verified queue;
the host never delegates a theme download to a browser. Only then does it create an Android
`PackageInstaller` session. Android presents the final consent surface. Pending downloads and open
sessions can be cancelled, and package add/replace/remove broadcasts trigger a complete source/theme
rescan and state reconciliation. Installer-result callbacks are accepted only for the exact active
PackageInstaller session; a package broadcast never substitutes for signed trust reconciliation.
The host persists the session ID and expected version before commit, validates restored sessions
against Android after process recreation, and routes update notifications directly to Sources.

This uses Mihon's queue, state, and package-broadcast pattern. Third-party code still never enters the
host process, and PaperReader verifies artifact hash and size before installation.

## Signing an index

Create an Ed25519 key and keep the private key outside Git:

```bash
openssl genpkey -algorithm ED25519 -out extension-store-private.pem
python tools/sign_extension_index.py \
  --index extension-index.json \
  --private-key extension-store-private.pem \
  --output extension-index.signed.json
```

Publish the Base64 raw public key and its SHA-256 fingerprint. Store refresh preserves the last
verified index when network, signature, schema, rollback, or equivocation checks fail.
