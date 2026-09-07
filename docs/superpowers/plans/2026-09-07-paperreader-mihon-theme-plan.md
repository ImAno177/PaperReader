# PaperReader Mihon-inspired theme implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a selectable Mihon-inspired Material 3 theme and semantic token roles while keeping Neobrutalism as PaperReader's default.

**Architecture:** Extend the existing `PaperThemeTokens` source of truth and map the new roles into one Material 3 `ColorScheme`. The preset is selected by existing preferences; community themes remain declarative overrides and the default fallback remains Neo.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX preferences/StateFlow, existing PaperReader icon and community-theme validation.

**Spec:** `docs/superpowers/specs/2026-09-07-paperreader-architecture-mvvm-plugin-ux-design.md`

## Global Constraints

- `NEOBRUTALISM` remains the default and unknown stored keys resolve to it.
- Use semantic Material 3 roles rather than per-screen hard-coded colors.
- Maintain light/dark contrast, 48 dp targets, reduced-motion/accessibility behavior, and community theme validation.
- Do not add dynamic color as the new default or remove the existing community theme contract.
- Do not copy Mihon code or icons; use the published scheme as a visual reference and preserve attribution policy.
- Run theme checks and local emulator inspection; no new GitHub visual/test runner.

## File map

- Modify `app/src/main/kotlin/dev/paperreader/app/ui/theme/Theme.kt` for preset/role mapping.
- Modify/create `app/src/main/kotlin/dev/paperreader/app/ui/theme/BuiltinThemePalette.kt` and
  `NeobrutalismThemePack.kt` for semantic values.
- Create `app/src/main/kotlin/dev/paperreader/app/ui/theme/MihonThemePack.kt` for the new light/dark
  token set.
- Modify `app/src/main/kotlin/dev/paperreader/app/ui/screen/AppearanceScreen.kt`,
  `app/src/main/kotlin/dev/paperreader/app/settings/PaperReaderPreferences.kt`,
  `app/src/main/kotlin/dev/paperreader/app/ui/theme/BuiltinIconPacks.kt`,
  `app/src/main/kotlin/dev/paperreader/app/reader/ReaderTheme.kt`, and
  `app/src/main/res/values/strings.xml` for selection copy and exhaustive preset handling.
- Reuse existing theme contrast/icon tests and connected appearance flows.

## Task 1: Expand semantic tokens without changing Neo output

**Files:**

- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/theme/Theme.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/theme/NeobrutalismThemePack.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/theme/CommunityPaperTheme.kt` only if the shared token contract requires a neutral default
- Test: existing theme contrast/icon tests under `app/src/test`

**Interfaces:**

- `PaperThemeTokens` gains only Material 3 roles consumed by the implementation: tertiary/on-tertiary, tertiary containers, error containers, outline variant, and surface-container levels.
- `PaperThemePreset.MIHON` is a stable storage key; `fromStorageKey(null/unknown)` still returns `NEOBRUTALISM`.

- [ ] **Step 1: Add the enum value and storage behavior.**

  ```kotlin
  enum class PaperThemePreset(val storageKey: String) {
      NEOBRUTALISM("neobrutalism"),
      MIHON("mihon"),
  }
  ```

  Keep the existing companion fallback unchanged.

- [ ] **Step 2: Add role fields with Neo-compatible values.**

  Map old `surface`, `surfaceMuted`, `border`, `danger`, and status values into the new roles so
  existing screens render the same pixels when Neo is selected. Avoid introducing new default accent
  colors in Neo.

- [ ] **Step 3: Map all roles in `materialScheme`.**

  Use `darkColorScheme`/`lightColorScheme` with explicit surface-container, outline, and error roles.
  Use `contrastForeground` for on-color values where the token is not already fixed by the palette.

- [ ] **Step 4: Run existing theme tests and host lint.**

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests "*Theme*" hostLint --no-configuration-cache
  ```

- [ ] **Step 5: Commit the token seam.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/ui/theme
  git commit -m "feat(theme): add semantic material roles"
  ```

## Task 2: Implement the Mihon-inspired palette

**Files:**

- Create: `app/src/main/kotlin/dev/paperreader/app/ui/theme/MihonThemePack.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/theme/Theme.kt`
- Modify: `THIRD_PARTY_NOTICES.md` only if the implementation copies attributable code/assets rather than color concepts

**Interfaces:**

- `mihonThemeTokens(dark: Boolean): PaperThemeTokens` returns a complete light or dark token set.
- `paperThemeTokens` dispatches `MIHON` to this pack and preserves `NEOBRUTALISM` to the existing pack.

- [ ] **Step 1: Define light and dark role values.**

  Use the Mihon source snapshot pinned at `3a64c8d65cf9fe4434645994642db440c73aa70a` in
  `D:/research-new/artifacts/mihon-research` as the reference for primary/secondary/tertiary and
  surface-container relationships. Keep all text/on-color values explicit and readable; do not use
  a near-black surface container where Material 3 guidance requires separation from scrolling content.

- [ ] **Step 2: Add typography/shape mapping.**

  Use existing PaperReader font roles and shape calculation. Mihon inspiration changes color/surface
  behavior, not the validated PaperReader icon set or community theme parser.

- [ ] **Step 3: Verify token completeness.**

  Add an existing-style unit assertion only if current tests cannot enumerate all roles; prefer
  extending the nearest contrast test instead of creating a pixel test. Every role used by
  `materialScheme` must be non-default in both light/dark values.

- [ ] **Step 4: Run local theme checks.**

  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --tests "*Theme*" :app:lintDebug --no-configuration-cache
  ```

- [ ] **Step 5: Commit the palette.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/ui/theme THIRD_PARTY_NOTICES.md
  git commit -m "feat(theme): add mihon-inspired color preset"
  ```

## Task 3: Expose the preset in Appearance

**Files:**

- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/screen/AppearanceScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: existing appearance ViewModel/preferences from the MVVM foundation branch
- Test: existing appearance unit/connected tests

**Interfaces:**

- Appearance state lists `NEOBRUTALISM`, `MIHON`, and validated community themes.
- Selecting MIHON persists `mihon`; a missing extension/theme does not overwrite the built-in preset.

- [ ] **Step 1: Add English labels and summaries.**

  Keep UI copy in resources. State explicitly that Neobrutalism is the default and Mihon is an
  optional Material 3 palette; do not claim Mihon affiliation.

- [ ] **Step 2: Render the preset selector through existing semantics.**

  Every option has a 48 dp target, selected semantics, and light/dark preview that does not require a
  new preview-only code path.

- [ ] **Step 3: Persist and restore selection.**

  Reuse `PaperReaderPreferences.setThemeKey` and existing `PaperReaderTheme` resolution. Unknown
  keys still restore Neo.

- [ ] **Step 4: Run host gate and connected appearance flow.**

  ```powershell
  .\gradlew.bat hostUnitTest hostLint :app:assembleDebug --no-configuration-cache
  .\gradlew.bat :app:connectedDebugAndroidTest -PpaperReaderConnectedTestApplicationIdSuffix=.uitest
  ```

- [ ] **Step 5: Commit the appearance integration.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/ui app/src/main/res/values/strings.xml
  git commit -m "feat(appearance): expose mihon theme preset"
  ```

## Task 4: Theme review gate

- [ ] **Step 1:** Inspect light/dark screenshots at normal and 130% font scale on `emulator-5554`.
- [ ] **Step 2:** Review semantic labels, contrast, target sizes, safe areas, and no-hidden-content behavior.
- [ ] **Step 3:** Generate the task review package and fix reviewed findings through the SDD loop.
- [ ] **Step 4:** Record the exact device/API and deferred visual work in the ledger.
