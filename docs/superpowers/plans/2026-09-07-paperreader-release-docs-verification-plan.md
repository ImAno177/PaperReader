# PaperReader local verification, merge, and documentation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify the reviewed branches locally, merge the approved implementation into `main`, build/install the final debug APK, and update host/source/site documentation with real emulator evidence.

**Architecture:** Verification is a local release gate over the merged result. Functional adb flows and performance measurements are recorded outside test sources; existing GitHub security workflows remain the security gate. Documentation is updated only after screenshots are binary-valid, visually inspected, and free of private data.

**Tech Stack:** Gradle wrapper, PowerShell, adb, Android emulator `emulator-5554`, `dumpsys gfxinfo`, APK signer tools, Markdown, Astro site.

**Spec:** `docs/superpowers/specs/2026-09-07-paperreader-architecture-mvvm-plugin-ux-design.md`

## Global Constraints

- Test functional behavior and benchmarks locally before any push or merge.
- Do not add GitHub functional/benchmark tests; use existing GitHub security workflows only.
- Do not wipe/factory-reset `emulator-5554` or replace the user's existing installation unnecessarily.
- Merge only reviewed, locally green branches; preserve feature branch refs and pre-merge APK artifacts.
- Documentation describes shipped behavior in present tense and links only to real files.
- Site follows `paperreader-site/design-system/paperreader-field-notes/MASTER.md`, UI Pro Max, and impeccable rules.

## File map

- Create local evidence under `D:/research-new/artifacts/paperreader-release-2026-09-07/`; do not commit raw logs or private paper files.
- Modify host `docs/TESTING.md`, `docs/SPEC.md`, `docs/ARCHITECTURE.md`, `docs/EXTENSIONS.md`, `README.md`, `CHANGELOG.md`, and screenshot assets after merge.
- Modify `paperreader-site/src/pages/index.astro` and its public screenshot assets only after the merged APK audit.
- Do not modify GitHub workflow test definitions except to correct documentation references if a reviewed branch changes a command.

## Task 1: Prepare branches and baseline evidence

**Files:**

- Modify: SDD ledger/progress artifacts only
- Create: local evidence directory outside Git

- [ ] **Step 1: Record branch/commit baselines.**

  ```powershell
  git -C D:\research-new\repos\paperreader status --short --branch
  git -C D:\research-new\repos\extensions\PaperReader-sources status --short --branch
  git -C D:\research-new\repos\paperreader-site status --short --branch
  ```

  Save HEADs, branch names, and existing APK SHA-256 values in the ledger before integration.

- [ ] **Step 2: Run the pre-change host/source gates.**

  ```powershell
  .\gradlew.bat hostUnitTest hostLint :app:assembleDebug --no-configuration-cache
  ```

  Run source unit/lint/build from `PaperReader-sources` using the local SDK path. Do not proceed
  past a baseline failure without recording its exact output and ruling whether it is pre-existing.

- [ ] **Step 3: Verify emulator state without wiping it.**

  ```powershell
  C:\Users\ImBot177\AppData\Local\Android\Sdk\platform-tools\adb.exe -s emulator-5554 get-state
  C:\Users\ImBot177\AppData\Local\Android\Sdk\platform-tools\adb.exe -s emulator-5554 shell getprop ro.build.version.sdk
  ```

## Task 2: Run local functional adb audit

**Files:**

- Create: local action log, screenshots, UI XML captures, and summary under `D:/research-new/artifacts/paperreader-release-2026-09-07/`
- Modify: none in source test directories

**Interfaces:**

- Use the final merged debug APK and the declared arXiv source APK/configuration.
- Capture six-paper readable/download flows: Attention, CGP-Tuning, LoRA, Llama 2, ResNet, InstructGPT.

- [ ] **Step 1: Install the final debug APK without clearing data.**

  ```powershell
  adb -s emulator-5554 install -r D:\research-new\artifacts\PaperReader-debug.apk
  adb -s emulator-5554 shell am force-stop dev.paperreader.app
  adb -s emulator-5554 shell monkey -p dev.paperreader.app 1
  ```

- [ ] **Step 2: Exercise search/save/open/read/back/find/contents.**

  Use `uiautomator dump`, `input tap/text/swipe`, and screenshots. Record the paper, action, visible
  result, and any visual defect. Confirm reader Back returns to the correct detail/library position.

- [ ] **Step 3: Exercise the two-lane readable path.**

  For each paper, record source/body hash, asset metadata count, asset files, total bytes, figure
  captions, missing-asset warnings, cache reopen, offline reopen, and one self-contained export.
  Confirm body HTML has no script/event-handler markup and the WebView does not fetch the network.

- [ ] **Step 4: Exercise PDF/download queue.**

  Request a PDF, capture live percentage, open More → Download queue, verify back, cancel/retry/remove
  behavior, process recreation, and persisted terminal rows. Confirm active aggregate progress updates.

- [ ] **Step 5: Exercise the UX changes.**

  Verify All/Unread/Finished counts, academic author semantics on Llama 2, DOI display/copy/open,
  More summaries, Neo/Mihon/light/dark behavior, 130% font scale, reader chrome hide/show, find and
  contents while chrome is hidden.

## Task 3: Run local performance benchmark

**Files:**

- Create: local benchmark CSV/Markdown summary under `D:/research-new/artifacts/paperreader-release-2026-09-07/`
- Modify: `docs/TESTING.md` later with summarized results only

- [ ] **Step 1: Measure cold/warm launches.**

  ```powershell
  adb -s emulator-5554 shell am force-stop dev.paperreader.app
  adb -s emulator-5554 shell am start -W -n dev.paperreader.app/.MainActivity
  ```

  Record `TotalTime` for cold and warm starts, using the same device state for before/after values.

- [ ] **Step 2: Measure reader and asset work.**

  Record time from Read action to first visible content, HTML byte size, asset count/bytes, retries,
  cache publication duration, and retained offline reopen duration for the six-paper set.

- [ ] **Step 3: Capture rendering stability.**

  ```powershell
  adb -s emulator-5554 shell dumpsys gfxinfo dev.paperreader.app reset
  # perform the documented scroll/library flows
  adb -s emulator-5554 shell dumpsys gfxinfo dev.paperreader.app > D:\research-new\artifacts\paperreader-release-2026-09-07\gfxinfo.txt
  ```

  Record frame/jank evidence and WebView process stability; do not treat a single screenshot as a
  performance pass.

- [ ] **Step 4: Measure APK/install footprint.**

  Record final APK bytes, SHA-256, signer verification, install time, and process start success.

## Task 4: Review and merge branches

**Files:**

- Modify: Git refs only; no source edits unless a reviewed fix branch exists

- [ ] **Step 1: Complete per-branch task review and whole-branch review.**

  Review spec compliance and quality separately. Any fix is made by the assigned implementer and
  receives a scoped re-review; controller-side unreviewed fixes are not accepted.

- [ ] **Step 2: Re-run local gates on each branch tip.**

  Use exact host/source commands from the branch's report and record device/API for connected suites.

- [ ] **Step 3: Merge in dependency order.**

  Merge architecture foundation, plugin boundary/API, theme, and UX branches into host `main`; merge
  the source extension branch into `PaperReader-sources` `main` only after its local build/fixture gate.
  Preserve the feature branch refs and copy the pre-merge debug APK into the evidence directory.

- [ ] **Step 4: Build final merged APK.**

  Use the exact debug source properties required by the local arXiv extension, then copy the APK to
  `D:/research-new/artifacts/PaperReader-debug-final.apk`. Verify package, version, signer, and SHA-256.

## Task 5: Update host documentation

**Files:**

- Modify: `docs/SPEC.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/EXTENSIONS.md`
- Modify: `docs/TESTING.md`
- Modify: `README.md`, `CHANGELOG.md`, `THIRD_PARTY_NOTICES.md` when attribution is required
- Create/replace: valid PNGs under `docs/screenshots/`

- [ ] **Step 1: Update product/architecture/extension behavior.**

  Document screen-level state ownership, Mihon preset/default Neo behavior, plugin-owned arXiv HTML,
  generic asset lane, no figure-count cap, retained quotas, and the reader chrome state machine.

- [ ] **Step 2: Update testing evidence.**

  Include exact Gradle commands, emulator/API, six-paper adb audit, asset counts, benchmark metrics,
  security workflow ownership, and the explicit absence of GitHub functional/benchmark tests.

- [ ] **Step 3: Publish screenshots only after inspection.**

  Pull PNGs with `adb shell screencap -p` followed by `adb pull`, inspect with `view_image`, confirm
  valid PNG signatures and no credentials/private files, then update README/docs references.

- [ ] **Step 4: Run Markdown/link checks and commit docs.**

  ```powershell
  git diff --check
  git add docs README.md CHANGELOG.md THIRD_PARTY_NOTICES.md
  git commit -m "docs: record local release audit and new UI"
  ```

## Task 6: Update PaperReader site

**Files:**

- Modify: `paperreader-site/src/pages/index.astro`
- Modify/create: site public screenshot assets and any page-specific design-system notes
- Test: local site build/lint/manual responsive inspection

- [ ] **Step 1: Read the existing master/page design-system rules before editing.**

  Keep the tactile editorial palette, Outfit/DM Mono pairing, reserved image dimensions, focus states,
  reduced-motion behavior, and responsive widths from the master file.

- [ ] **Step 2: Replace stale product evidence with final emulator captures.**

  Label captures as shipped local evidence; do not describe prototypes or stale branches as current.
  Add the new More/Library/Detail/reader/theme evidence only where it clarifies the product.

- [ ] **Step 3: Run local site verification.**

  Use the site's existing package scripts, then inspect at 375, 768, 1024, and 1440 widths plus
  reduced-motion mode. Check no horizontal overflow, no hidden content behind fixed navigation, and
  no emoji structural icons.

- [ ] **Step 4: Commit the site update.**

  ```powershell
  git add src/pages public
  git commit -m "docs(site): refresh merged emulator evidence"
  ```

## Task 7: Final handoff

- [ ] **Step 1:** Verify host/source/site working trees and branch heads.
- [ ] **Step 2:** Verify final APK link, SHA-256, signer, emulator package, and installed version.
- [ ] **Step 3:** Report merged branches, preserved feature refs, local commands/results, benchmark summary, screenshots, and intentionally deferred work.
- [ ] **Step 4:** Mark the SDD ledger complete only after final whole-branch review and docs/site review are clean.
