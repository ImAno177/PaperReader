# PaperReader Library, Detail, More, and reader UX implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify the requested user-facing screens while preserving working actions, accessibility, provenance, and reader controls.

**Architecture:** Use the screen ViewModels from the foundation branch, keep presentation transformations beside each feature, and introduce only small reusable helpers where two real screens need the same behavior. Library status tabs use one state snapshot; Detail and reader changes preserve existing task/cache contracts.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, existing PaperReader tokens/icons, Android WebView reader chrome, local string resources.

**Spec:** `docs/superpowers/specs/2026-09-07-paperreader-architecture-mvvm-plugin-ux-design.md`

## Global Constraints

- Keep all current working search/read/download/cache/back/find/contents/PDF actions.
- Keep interactive targets at least 48 dp and provide TalkBack semantics for truncated data.
- Use English resource strings; do not embed user-visible copy in Kotlin.
- Do not add emoji icons, pixel-specific test files, or generic UI wrappers.
- Authors use academic `et al.` truncation with full names available to accessibility/detail.
- DOI is explicit and safe; remote URLs remain HTTPS/user-info checked.
- Reader WebView remains network-blocked and JavaScript remains limited to existing app-owned commands.

## File map

- Modify `app/src/main/kotlin/dev/paperreader/app/ui/model/LibraryPresentation.kt` and `PaperUiModels.kt` for deterministic academic author/DOI presentation data.
- Modify `LibraryScreen.kt` and `LibraryPaperCards.kt` for All/Unread/Finished controls and minimal cards.
- Modify `MoreScreen.kt` and `PaperComponents.kt` only for genuine row/group behavior.
- Modify `DetailScreen.kt`, `ManifestationCard.kt`, and `strings.xml` for DOI/header and redundant labels.
- Modify `ReadablePaperWebView.kt`, `ReadablePaperActivity.kt`, and `ReadablePaperChrome.kt` for the chrome state machine.
- Reuse existing UI tests/connected tests and the adb audit; do not add a pixel-test suite.

## Task 1: Add deterministic academic author and DOI presentation helpers

**Files:**
- Create: `app/src/main/kotlin/dev/paperreader/app/ui/model/AcademicPresentation.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/model/PaperUiModels.kt`
- Modify: existing presentation unit tests nearest to `PaperUiModels`

**Interfaces:**
- `fun formatAcademicAuthors(authors: List<String>): String` returns `Unknown authors`, one author, `A and B`, or `A et al.` according to the existing English resource policy.
- `fun PaperUi.doiOrNull(): String?` returns the canonical DOI value without a `doi:` display prefix and rejects blank/invalid values through existing identifier normalization.
- The full author list remains available separately for semantics/detail.

- [ ] **Step 1: Define the exact display cases.**

  ```kotlin
  fun formatAcademicAuthors(authors: List<String>): String = when (val clean = authors.map(String::trim).filter(String::isNotBlank)) {
      emptyList<String>() -> "Unknown authors"
      listOf(clean.first()) -> clean.first()
      else -> if (clean.size == 2) "${clean[0]} and ${clean[1]}" else "${clean.first()} et al."
  }
  ```

  Use a resource for `Unknown authors` and the `and`/`et al.` fragments if the codebase's existing
  localization pattern requires it; preserve full authors for `contentDescription`.

- [ ] **Step 2: Add DOI selection to the presentation model.**

  Prefer the canonical `IdentifierType.DOI` entry from `PaperUi.identifiers`, return null for blank
  or malformed values, and leave arXiv/PMID/PMCID display unchanged.

- [ ] **Step 3: Run existing model tests.**

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests "*PaperUi*" --tests "*Library*" --no-configuration-cache
  ```

- [ ] **Step 4: Commit the presentation helpers.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/ui/model
  git commit -m "feat(ui): add academic author and doi presentation"
  ```

## Task 2: Simplify Library filters and cards

**Files:**
- Modify: `LibraryScreen.kt`, `LibraryPaperCards.kt`, `LibraryPresentation.kt`
- Modify: `strings.xml` and `PaperComponents.kt` only when existing resources/components cannot express the state
- Test: existing Library tests and local adb inspection

**Interfaces:**
- Primary status control exposes `ALL`, `UNREAD`, and `FINISHED` with counts from the same `LibraryUiState` list.
- `READING` and `ANNOTATED` remain available through secondary filter UI; no capability is deleted.
- Cards use `formatAcademicAuthors`, compact metadata, one conditional status/progress row, and accessible full-title/full-author semantics.

- [ ] **Step 1: Add the primary status selector.**

  Render three 48 dp selectable targets with selected semantics and count labels. Keep the active
  filter in the Library ViewModel, not local duplicate state.

- [ ] **Step 2: Move secondary filters behind the existing controls.**

  Preserve query, collections, sort, Reading, and Annotated. A user must still be able to reach every
  existing filter with a visible action and without relying on a fragile hitbox.

- [ ] **Step 3: Reduce list-card density.**

  Keep title, academic author summary, provider/year/identifier, state/progress, and Read/Open actions.
  Remove blank reserved rows and duplicate labels; maintain a minimum 48 dp action target.

- [ ] **Step 4: Reduce grid-card density.**

  Keep the one-line title rule and one compact state row. Omit authors and redundant provider text in
  grid cards as already documented. Do not hide the full title from semantics.

- [ ] **Step 5: Run local UI/model checks.**

  ```powershell
  .\gradlew.bat hostUnitTest hostLint :app:assembleDebug --no-configuration-cache
  ```

- [ ] **Step 6: Commit the Library UX.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/ui app/src/main/res/values/strings.xml
  git commit -m "feat(library): simplify status filters and paper cards"
  ```

## Task 3: Simplify More and optimize Paper Detail

**Files:**
- Modify: `MoreScreen.kt`, `PaperComponents.kt`, `DetailScreen.kt`, `ManifestationCard.kt`
- Modify: `strings.xml`
- Test: existing Detail/MainNavigation tests and local adb inspection

**Interfaces:**
- More consumes `MoreUiState` from the foundation branch and keeps grouped preference rows with concise summaries.
- Detail shows DOI near the title/author header and keeps full identifier data below.
- The redundant attached-image labels are absent from repeated card/header presentation; legal license/provenance remains explicit.

- [ ] **Step 1: Review More rows against the Mihon model.**

  Keep Personalize, Reading, Data & sources, Insights, and About groups. Remove only duplicate status
  prose that the destination already displays. Preserve forward affordances, section labels, and
  summary states for active/failed work.

- [ ] **Step 2: Add DOI to the Detail header.**

  Render a labeled DOI row when `paper.doiOrNull()` is present. Copy uses Android clipboard with a
  short accessibility confirmation; open uses a validated `https://doi.org/<canonical-doi>` URI.
  Keep all other identifiers in the existing section.

- [ ] **Step 3: Use academic author summary in Detail.**

  Display `formatAcademicAuthors(paper.authors)` and attach the full joined author list as semantics.
  Do not discard authors from the domain model.

- [ ] **Step 4: Remove redundant mobile/license badges.**

  Delete only the repeated “License shown in verified mobile source” and “Mobile reading available”
  labels represented by the supplied image. Keep the actual license value, provenance disclosure,
  Read action, and export/source actions where they carry distinct information.

- [ ] **Step 5: Run Detail/navigation tests and host gate.**

  ```powershell
  .\gradlew.bat hostUnitTest hostLint :app:assembleDebug --no-configuration-cache
  .\gradlew.bat :app:connectedDebugAndroidTest -PpaperReaderConnectedTestApplicationIdSuffix=.uitest
  ```

- [ ] **Step 6: Commit the More/Detail UX.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/ui app/src/main/res/values/strings.xml
  git commit -m "feat(ui): simplify more and paper detail"
  ```

## Task 4: Make reader chrome behavior stateful and smart

**Files:**
- Modify: `app/src/main/kotlin/dev/paperreader/app/reader/ReadablePaperWebView.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/reader/ReadablePaperActivity.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/reader/ReadablePaperChrome.kt`
- Modify: `app/src/main/res/layout/activity_readable_paper.xml` only for safe insets/semantics if required
- Test: existing reader/WebView tests and local adb audit

**Interfaces:**
- WebView emits meaningful direction/interaction signals; Activity owns a small chrome state machine.
- `showReadableReaderChrome`/`hideReadableReaderChrome` remain the only visibility transition entry points.
- Hidden toolbar/provenance views are removed from accessibility traversal; system Back and essential menu actions remain reachable.

- [ ] **Step 1: Define chrome states and transition conditions.**

  Use `VISIBLE`, `HIDDEN`, and `LOCKED_VISIBLE` (find/modal/near-top) or an equivalent sealed state.
  Hide only after downward movement past the existing direction threshold and a non-top content
  threshold. Reveal on upward movement or tap; do not reveal from noisy sub-threshold scroll events.

- [ ] **Step 2: Preserve reader action wiring.**

  Search, contents, annotations, layout, source, original PDF, citation return, and system Back must
  continue to invoke the current callbacks. The change is visibility state, not an action removal.

- [ ] **Step 3: Respect reduced motion.**

  Keep the current `ValueAnimator.areAnimatorsEnabled()` branch and ensure disabled animation still
  applies final visibility/accessibility state immediately.

- [ ] **Step 4: Run reader tests and build.**

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests "*Readable*" --tests "*Reader*" hostLint :app:assembleDebug --no-configuration-cache
  ```

- [ ] **Step 5: Commit reader chrome changes.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/reader app/src/main/res/layout/activity_readable_paper.xml
  git commit -m "feat(reader): improve smart chrome visibility"
  ```

## Task 5: UX review gate

- [ ] **Step 1:** Use adb to inspect Library All/Unread/Finished, long-author Llama 2, DOI detail, More, queue, and reader flows.
- [ ] **Step 2:** Inspect 375 px-equivalent portrait, landscape, 130%+ font scale, light/dark, and reduced motion.
- [ ] **Step 3:** Generate the diff review package and fix reviewed findings through the SDD loop.
- [ ] **Step 4:** Record screenshots and deferred polish in the ledger; do not publish screenshots until the merged local run.
