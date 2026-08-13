# Third-party notices

PaperReader's source is Apache-2.0. The Android application is built from open-source dependencies,
including:

| Component | Version/source | License |
|---|---|---|
| AndroidX libraries, including Room, WorkManager, Compose, Navigation, and PDF | versions declared in Gradle | Apache-2.0 |
| Kotlin and kotlinx.coroutines/serialization | versions declared in Gradle | Apache-2.0 |
| OkHttp | 5.1.0 | Apache-2.0 |
| jsoup | 1.22.2 | MIT |
| Material Components / Material 3 | versions declared in Gradle | Apache-2.0 |

The authoritative dependency versions are in `build.gradle.kts`, `app/build.gradle.kts`, and
`logic/build.gradle.kts`. Transitive artifacts retain their original copyright and license notices.
Test-only dependencies are not bundled in the APK.

Paper metadata and paper content are not part of this source-code license. Each provider record and
downloaded document retains its own source, provenance, copyright, and license status. In particular,
arXiv metadata and e-print content have different reuse terms; PaperReader does not grant rights to
redistribute downloaded papers.
