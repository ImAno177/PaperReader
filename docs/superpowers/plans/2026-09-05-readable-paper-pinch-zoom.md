# Readable Paper Pinch Zoom Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let readers pinch-zoom the local readable-paper WebView with two fingers so text and figures can be inspected at a larger scale, then validate the feature and the pending Android toolchain upgrade before publishing the next 0.1.x release.

**Architecture:** Keep zoom inside the existing `ReadablePaperWebView`/`WebSettings` boundary. Android WebView owns the scale gesture and focal-point behavior; the reader keeps its existing CSS text-zoom preference and hides WebView's legacy on-screen zoom buttons. Dependency/toolchain work is validated as a separate integration step so the zoom change remains easy to review.

**Tech Stack:** Kotlin, Android WebView, Android Gradle Plugin, Gradle wrapper, GitHub CLI, local Android API 36/37 emulator.

**Spec:** `docs/SPEC.md` readable-paper and release-readiness requirements.

## Global Constraints

- Pinch zoom is enabled only for the readable-paper WebView; the existing text-size setting remains unchanged.
- The legacy WebView zoom controls remain hidden; users use a two-finger pinch gesture.
- Test locally on the declared emulator before any push, merge, or release action.
- Do not add or modify test source files for this task; use the existing host, lint, connected-test, APK, and manual emulator gates.
- Preserve the existing queue UI and readable-HTML/SVG changes already present in the worktree.
- Do not merge a GitHub change while its required checks are red; inspect and repair the compileSdk/toolchain incompatibility first.
- The next release after `v0.1.9` is `v0.1.10`, with a monotonically increasing version code supplied to the release workflow.

---

### Task 1: Preserve image-regression evidence

**Files:**

- Read: `logic/src/main/kotlin/dev/paperreader/logic/reader/ArxivHtmlSanitizer.kt`
- Read: `logic/src/main/kotlin/dev/paperreader/logic/reader/ArxivReadablePaperLoader.kt`
- Read-only local artifacts: `D:/research-new/paperreader-*.body.html`

**Interfaces:**

- Consumes: the current emulator APK and exact-version arXiv HTML pages.
- Produces: a local evidence table for Attention, LoRA, ResNet, InstructGPT, Llama 2, and the already checked CGP-Tuning paper.

- [ ] **Step 1: Record source-side asset counts**

  Resolve each paper's relative `<img src>` and `<object data>` URL against its arXiv HTML URL, then record HTTP status, media type, and byte size for each figure asset. Keep the output outside the repository.

- [ ] **Step 2: Record app-side cache counts**

  Pull only app-private cache bodies/manifests with `adb exec-out run-as`, count embedded raster/SVG data URIs and `paperreader-figure-unavailable` markers, and correlate warnings with the source URL.

- [ ] **Step 3: Record crash evidence for large papers**

  If a paper leaves the reader or the process restarts, capture the local `logcat` entry and classify it as conversion/sanitizer failure, asset policy rejection, or process memory failure. Do not change production code in this task step.

---

### Task 2: Enable native two-finger reader zoom

**Files:**

- Modify: `app/src/main/kotlin/dev/paperreader/app/reader/ReadablePaperWebViewConfiguration.kt`
- Read: `app/src/main/kotlin/dev/paperreader/app/reader/ReadablePaperWebView.kt`
- Read: `app/src/main/kotlin/dev/paperreader/app/reader/ReadablePaperRendering.kt`

**Interfaces:**

- Consumes: the existing `configureReadablePaperWebView` setup and `meta viewport` renderer output.
- Produces: a readable-paper WebView configured with pinch zoom enabled, legacy zoom buttons hidden, and the existing text-zoom preference preserved.

- [ ] **Step 1: Enable WebView scale gestures**

  In the `WebSettings` block, set `builtInZoomControls = true`, keep `displayZoomControls = false`, and set `setSupportZoom(true)`. Leave `textZoom = textZoom`, JavaScript policy, network blocking, local renderer URL, and image CSS unchanged.

- [ ] **Step 2: Keep the interaction boundary narrow**

  Do not add a custom `ScaleGestureDetector` or transform the HTML manually. The native WebView already handles pointer tracking, focal-point zooming, scrolling, and accessibility for a local HTML document; a second gesture layer would compete with text selection and vertical scrolling.

- [ ] **Step 3: Inspect the diff**

  Confirm the production diff changes only the WebView zoom settings plus any release/changelog documentation explicitly required by the final version bump.

---

### Task 3: Run the complete local verification gate

**Files:**

- Read: `docs/TESTING.md`
- Read: `.github/workflows/android-ci.yml`
- Artifact: `app/build/outputs/apk/debug/app-debug.apk`

**Interfaces:**

- Consumes: the zoom-enabled source tree and local Android SDK/emulator.
- Produces: passing local build/lint/unit/connected-test results and a manually installed APK with reader zoom smoke evidence.

- [ ] **Step 1: Run host verification**

  Run `./gradlew.bat hostUnitTest hostLint :app:assembleDebug` from the repository root and require `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run existing connected tests**

  Run the repository's existing `:extension-api:connectedDebugAndroidTest :logic:connectedDebugAndroidTest :app:connectedDebugAndroidTest` command with its test application-id suffix and require every existing test to pass. Do not add tests.

- [ ] **Step 3: Install only after local gates pass**

  Install the debug APK on `emulator-5554`, clear/reopen only the app state needed for the smoke test, and verify the reader opens an existing cached paper without a network dependency.

- [ ] **Step 4: Exercise two-finger zoom manually**

  Open an image-bearing cached paper, capture the initial WebView scale/viewport state, perform a real two-pointer pinch-out and pinch-in using the emulator's multi-touch input path, and verify the page becomes wider/larger and returns toward its original scale without crashing, losing the back button, or breaking vertical scroll/text selection. If the input bridge cannot synthesize two pointers, use the emulator UI's multi-touch controls and record that limitation rather than claiming the gesture passed.

- [ ] **Step 5: Recheck image papers**

  Reopen Attention and one raster-heavy paper, verify figures and captions are visible at the reader level, and retain the local cache/log evidence for the known image failures separately from the zoom result.

---

### Task 4: Validate compileSdk 37 and review PR #48

**Files:**

- Modify only if required by local/CI evidence: `app/build.gradle.kts`
- Review: `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts`, `logic/build.gradle.kts`
- Review: GitHub PR `#48`

**Interfaces:**

- Consumes: the current main branch, Dependabot PR #48, and installed `platforms;android-37.0`.
- Produces: a locally verified compileSdk 37/toolchain combination and a documented decision to merge or hold PR #48.

- [ ] **Step 1: Review the failing checks and diff**

  Use `gh pr view 48`, `gh pr diff 48`, and `gh run view` to inspect dependency changes and required failures. The current failure is expected to be checked against the four dependencies requiring compileSdk 37 or later.

- [ ] **Step 2: Test compileSdk 37 locally**

  Set the app compile SDK to the installed API 37 level without changing `targetSdk` unless a dependency requires it, then run the complete local host/lint/assemble and connected gates. Keep the API minor level compatible with the SDK actually installed.

- [ ] **Step 3: Integrate the dependency update safely**

  After the zoom branch is merged, update/merge PR #48 only when its branch is based on the compileSdk-37-compatible main and all required checks are green. Resolve any Kotlin/AGP/KSP/Gradle incompatibility locally before using GitHub merge.

- [ ] **Step 4: Re-run the merged main gate**

  Verify the resulting main commit with host tests, lint, debug/release assembly, and connected tests before release preparation.

---

### Task 5: Commit, PR review, merge, and release `v0.1.10`

**Files:**

- Modify: `CHANGELOG.md`
- Review: all files included in the feature PR and the release diff

**Interfaces:**

- Consumes: green local verification, clean commit history, authenticated `gh`, and a green GitHub workflow.
- Produces: a reviewed/merged PR, merged PR #48, and GitHub release `v0.1.10` with the signed APK/SBOM artifacts.

- [ ] **Step 1: Commit the feature branch**

  Review `git diff`, ensure no generated local artifacts or test additions are staged, then create a conventional commit containing the requested feature and preserved user changes.

- [ ] **Step 2: Push and create the feature PR**

  Push the branch only after local verification, create one PR with `gh pr create`, and describe the native WebView pinch-zoom rationale and image-test evidence without adding a redundant checklist section.

- [ ] **Step 3: Review the PR**

  Inspect the pushed diff and CI checks, request/fix any issue found, and merge only after required checks are green. Delete the remote feature branch only if the merge operation does so safely.

- [ ] **Step 4: Prepare the release notes**

  Move the relevant Unreleased entries into `## [0.1.10] - 2026-09-05`, retain an empty Unreleased section for future work, and ensure the version code is greater than the previous release's code.

- [ ] **Step 5: Dispatch and verify the release workflow**

  Run the repository's release workflow with `version_name=0.1.10` and the next version code only after merged-main CI is green. Verify the published tag, signed APK, checksum/SBOM assets, and release page; report any missing signing secret or workflow gate as a blocker.

---

## Self-review checklist

- Scope covers source image testing, pinch zoom, local verification, compileSdk 37, PR #48, review/merge, and the next 0.1.x release.
- No test source file is created or modified.
- The zoom interface uses existing `ReadablePaperWebView` configuration and does not duplicate WebView gesture handling.
- Release actions are gated on both local and GitHub verification.
