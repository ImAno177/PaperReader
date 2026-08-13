# Extension SDK

PaperReader extensions are separate Android applications. They communicate with the host through
the versioned `dev.paperreader:extension-api` AIDL contract and run under a different Linux UID.
Third-party code is never loaded into the PaperReader process.

Two reference implementations are maintained separately:

- [Source extension sample](https://github.com/ImAno177/PaperReader-source-sample) — queries the real
  OpenAlex API and returns neutral paper records.
- [Theme extension sample](https://github.com/ImAno177/PaperReader-theme-sample) — supplies complete
  light/dark palettes, typography, shape tokens, and all semantic icons.

## Current availability

The SDK and both host transports are implemented and exercised with external APKs. The public
signed extension index and end-user install/update flow are not implemented yet. Development builds
accept only an explicit package, service, version, descriptor, and certificate SHA-256 supplied at
build time. Release builds do not trust either sample automatically.

## Build against the SDK

Until the SDK is published to a Maven repository, use a composite build. Clone PaperReader into the
extension repository as `PaperReader`, or pass its location:

```bash
./gradlew :app:assembleDebug -PpaperReaderSdkPath=/path/to/PaperReader
```

The samples replace `dev.paperreader:extension-api:0.1.0` with the local `:extension-api` project.
The API requires Android 9 or newer and Java/Kotlin bytecode target 17.

## Source extensions

A source APK exports one service for
`dev.paperreader.extensions.api.action.PAPER_SOURCE`. Its manifest metadata must declare API version
`1` and kind `source`. The descriptor declares a stable provider ID, display name, request interval,
and supported capabilities.

Search and detail requests are asynchronous and cancellable. Responses use neutral records rather
than host domain or database types. A source must preserve provider record IDs and provenance, bound
its network responses, validate URLs, expose rate limiting through `retryAfterMillis`, and return no
more than 50 records per page.

## Theme extensions

A theme APK exports one service for
`dev.paperreader.extensions.api.action.PAPER_THEME`. Its manifest metadata must declare API version
`1` and kind `theme`.

Each theme is a complete declarative visual system:

- light and dark semantic color palettes;
- title, body, and label font families;
- corner, border, shadow, and decoration tokens;
- every `ThemeSemanticIcon` supplied as bounded ASCII path data.

Icon paths use a `2400 × 2400` viewport and are limited to 64 KiB each. The host parses and renders
them; extensions cannot inject Compose code, layouts, arbitrary resources, or file paths. Missing,
oversized, or malformed icons reject the whole theme instead of producing a partially themed UI.

## Trust and runtime limits

Before binding, PaperReader verifies the exact package, exported service component, version code,
certificate SHA-256, API metadata, extension kind, descriptor, and separate UID. Services should
also verify PaperReader's package and signing certificate on every Binder entry point; both samples
demonstrate this fail-closed check through `paperReaderHostSignerSha256`.

The host uses explicit Binder intents, bounded parcels and file descriptors, five-second theme
requests, bounded source requests, cancellation propagation, and strict decoders. It never sends an
extension its Room database, private storage root, arbitrary host file path, global credential, or
unrestricted intent.

For local host testing, provide the `paperReaderDevSource*` or `paperReaderDevTheme*` Gradle
properties defined in `app/build.gradle.kts`. Do not commit signing keys, private credentials, or a
personal certificate fingerprint.
