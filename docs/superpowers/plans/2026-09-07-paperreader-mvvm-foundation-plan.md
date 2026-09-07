# PaperReader screen-level MVVM/UDF implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the app-wide callback matrix with focused screen-level ViewModels and immutable UDF state while preserving current product behavior.

**Architecture:** Keep one application-scoped `PaperReaderLogic` facade, inject it into destination ViewModels, and let each route collect one screen state and dispatch typed actions. `PaperReaderApp` remains the composition root for global preferences, lifecycle, Android launchers, and effects; it does not own every screen's data.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Lifecycle ViewModel, StateFlow, Navigation Compose, Room/WorkManager through `PaperReaderLogic`.

**Spec:** `docs/superpowers/specs/2026-09-07-paperreader-architecture-mvvm-plugin-ux-design.md`

## Global Constraints

- Preserve `:app -> :logic -> :extension-api`; no UI imports in `:logic` and no DAO/HTTP/parser/Binder imports in screen code.
- Keep production Kotlin files below 600 lines by splitting at screen/state/lifecycle seams.
- ViewModels expose immutable `StateFlow` state and receive UI events; they do not hold `Context`, `Activity`, `View`, or `NavController`.
- Keep Android document pickers, notifications, PackageInstaller, and external URI launches in the UI/effect layer.
- Do not add a generic `BaseViewModel`, event bus, service locator, or pass-through wrapper.
- Do not add a new GitHub functional/benchmark runner; run existing host/connected gates locally.
- Preserve existing screen test seams and current working behaviors before visual simplification.

## File map

- Create `app/src/main/kotlin/dev/paperreader/app/ui/state/LoadState.kt` for the shared immutable load state and flow mapping extension.
- Create `app/src/main/kotlin/dev/paperreader/app/ui/ScreenViewModelFactory.kt` for explicit destination ViewModel construction from existing dependencies.
- Create focused ViewModels beside their screen state, following the repository's `ui/screen/<Feature>Screen.kt` organization.
- Modify `PaperReaderApp.kt` and `PaperReaderRoutes.kt` to remove broad state collection and callback forwarding.
- Modify `PaperReaderViewModel.kt` only while migrating behavior; delete it after every public action has an owning screen ViewModel.
- Reuse `PaperReaderSearchController`, `PaperReaderMetadataBackupController`, and `PaperReaderLocalPdfImportController` inside their owning ViewModels instead of rewriting domain behavior.
- Keep existing presentation models in `ui/model` unless a field is screen-specific and cannot be derived from the facade.

## Task 1: Introduce the screen-state seam

**Files:**
- Create: `app/src/main/kotlin/dev/paperreader/app/ui/state/LoadState.kt`
- Create: `app/src/main/kotlin/dev/paperreader/app/ui/ScreenViewModelFactory.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/PaperReaderViewModel.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/PaperReaderApp.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/PaperReaderRoutes.kt`

**Interfaces:**
- `LoadState` moves without changing its public cases: `Loading`, `Ready<T>`, and `Failed`.
- The factory consumes `PaperReaderLogic`, `PaperReaderPreferences`, `DownloadWorkScheduler`, and `MetadataRestoreSessionStore`, and produces only explicitly requested destination ViewModels.
- Existing `PaperReaderApp` behavior remains available while later tasks migrate one route at a time.

- [ ] **Step 1: Map current actions to owners.**

  Use the current `PaperReaderViewModel` methods as the inventory. The mapping must cover library,
  search, history, saved searches, detail, downloads, collections, imports, backup, source stores,
  provider enablement, and preferences before any old method is removed.

- [ ] **Step 2: Create the shared load state file.**

  Move the existing sealed type and the `Flow.asLoadState` behavior to a small file without changing
  failure semantics or cancellation propagation:

  ```kotlin
  sealed interface LoadState<out T> {
      data object Loading : LoadState<Nothing>
      data class Ready<T>(val value: T) : LoadState<T>
      data object Failed : LoadState<Nothing>
  }
  ```

- [ ] **Step 3: Add an explicit ViewModel factory seam.**

  Use AndroidX `viewModelFactory` initializers with concrete dependencies and no reflective registry.
  Task 1 adds the factory file and its dependency plumbing only; each later migration adds an
  initializer in the same change as its concrete destination ViewModel. Do not add a factory branch
  for a future or unused destination.

- [ ] **Step 4: Keep the old app ViewModel as a temporary adapter.**

  Compile the app with `PaperReaderViewModel` still serving unmigrated routes. Its state must not be
  duplicated in a new global store. The adapter may delegate to feature controllers until the final
  migration task removes it.

- [ ] **Step 5: Run the host compile/lint gate.**

  Run from `D:\research-new\repos\paperreader`:

  ```powershell
  .\gradlew.bat hostUnitTest hostLint :app:assembleDebug --no-configuration-cache
  ```

  Expected: PASS with no new lint baseline and no `LogicBoundaryTest` weakening.

- [ ] **Step 6: Commit the seam.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/ui
  git commit -m "refactor(app): add screen viewmodel seam"
  ```

## Task 2: Migrate Library and its state

**Files:**
- Create: `app/src/main/kotlin/dev/paperreader/app/ui/screen/LibraryViewModel.kt`
- Create: `app/src/main/kotlin/dev/paperreader/app/ui/screen/LibraryUiState.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/screen/LibraryScreen.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/screen/LibraryPaperCards.kt`
- Modify: `app/src/main/kotlin/dev/paperreader/app/ui/PaperReaderRoutes.kt`
- Test: existing Library presentation/UI tests under `app/src/test` and `app/src/androidTest`

**Interfaces:**
- `LibraryViewModel.uiState: StateFlow<LibraryUiState>` owns papers, collections, layout preference, query, status filter, collection filter, and sort order.
- `LibraryViewModel.onAction(LibraryAction)` owns filter/layout mutations and calls the existing facade for paper opens/status operations.
- `LibraryScreen` continues to accept a state-driven seam so current Compose tests do not need a navigation-controller test double.

- [ ] **Step 1: Define the immutable state.**

  Keep load failures explicit and derive counts from the same paper list used for visible rows:

  ```kotlin
  data class LibraryUiState(
      val papers: LoadState<List<PaperUi>> = LoadState.Loading,
      val collections: LoadState<List<PaperCollectionUi>> = LoadState.Loading,
      val layout: LibraryLayout = LibraryLayout.LIST,
      val query: String = "",
      val statusFilter: LibraryStatusFilter = LibraryStatusFilter.ALL,
      val selectedCollectionId: Long? = null,
      val sortOrder: LibrarySortOrder = LibrarySortOrder.RECENTLY_SAVED,
  )
  ```

- [ ] **Step 2: Move the existing flows into `LibraryViewModel`.**

  Subscribe to `logic.useCases.observeLibrary` and `observeCollections`, map domain models through
  `toPaperUi`/`toPaperCollectionUi`, and observe `preferences.libraryLayout`. Use
  `SharingStarted.WhileSubscribed(5_000)` and rethrow `CancellationException` exactly as the current
  adapter does.

- [ ] **Step 3: Hoist filter state into actions.**

  Replace screen-owned `rememberSaveable` filter values with action dispatch. Keep search-field
  visibility as UI-only state if it is not persisted; the query itself belongs in `LibraryUiState` so
  the visible count and list are one snapshot.

- [ ] **Step 4: Wire the route to the ViewModel.**

  The route obtains `LibraryViewModel`, collects `uiState` with lifecycle, and passes only state plus
  navigation lambdas to `LibraryScreen`. It must not pass the entire `PaperReaderViewModel`.

- [ ] **Step 5: Preserve existing behavior locally.**

  Run the existing Library unit/connected tests and the host gate. Record the exact commands in the
  agent report; do not add a screenshot-only test.

- [ ] **Step 6: Commit the Library migration.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/ui/screen app/src/main/kotlin/dev/paperreader/app/ui/PaperReaderRoutes.kt
  git commit -m "refactor(app): move library state to screen viewmodel"
  ```

## Task 3: Migrate discovery, history, and updates

**Files:**
- Create: `app/src/main/kotlin/dev/paperreader/app/ui/screen/DiscoverViewModel.kt`
- Create: `app/src/main/kotlin/dev/paperreader/app/ui/screen/HistoryViewModel.kt`
- Create: `app/src/main/kotlin/dev/paperreader/app/ui/screen/UpdatesViewModel.kt`
- Modify: `PaperReaderSearchController.kt`
- Modify: `HistoryScreen.kt`, `DiscoverScreen.kt`, `UpdatesScreen.kt`
- Modify: `PaperReaderRoutes.kt`, `PaperReaderViewModel.kt`
- Test: existing discovery/history/update tests under `app/src/test` and `app/src/androidTest`

**Interfaces:**
- `DiscoverViewModel` owns the existing search controller and exposes search/saved-search state without changing provider policy.
- `HistoryViewModel` owns `observeReadingHistory` and remove-history action.
- `UpdatesViewModel` owns saved-search refresh/update presentation and notification-facing state; permission launch remains a route effect.

- [ ] **Step 1: Define state/action pairs from the existing controllers.**

  Do not create a second search algorithm. Move the current controller instance and its StateFlows
  behind the screen ViewModel, retaining exact query cancellation, provider failure isolation, and
  saved-result identity behavior.

- [ ] **Step 2: Move history mapping and removal.**

  Use `ReadingHistoryEntry.toReadingHistoryUi()` and `logic.useCases.removeReadingHistory`; do not
  expose a repository or DAO to the Composable.

- [ ] **Step 3: Move updates actions.**

  Keep `SavedSearchRefreshScheduler` and notification permission as UI dependencies. The ViewModel
  emits the desired preference change; the route performs the permission request and reports the
  resulting availability.

- [ ] **Step 4: Rewire root routes and remove duplicate collection.**

  Each route collects only its own ViewModel. Delete corresponding fields and callbacks from the
  `PaperReaderNavigation` parameter list after the route compiles.

- [ ] **Step 5: Run local tests and commit.**

  ```powershell
  .\gradlew.bat hostUnitTest hostLint :app:assembleDebug --no-configuration-cache
  git add app/src/main/kotlin/dev/paperreader/app/ui
  git commit -m "refactor(app): isolate discovery history and updates state"
  ```

## Task 4: Migrate More, queue, detail, and branch screens

**Files:**
- Create: `MoreViewModel.kt`, `DownloadQueueViewModel.kt`, `PaperDetailViewModel.kt`
- Create: `CollectionsViewModel.kt`, `ReadingImportsViewModel.kt`, `DataBackupViewModel.kt`, `SourcesViewModel.kt`, `AppearanceViewModel.kt`, `SettingsViewModel.kt`, `StatsViewModel.kt`
- Modify: matching `ui/screen/*Screen.kt` files, `PaperReaderRoutes.kt`, and `PaperReaderViewModel.kt`
- Test: existing screen/unit/connected tests under `app/src/test` and `app/src/androidTest`

**Interfaces:**
- More state contains only summary inputs needed by More rows: library count, task summary, provider review counts, import/backup status, selected theme name, and notification/update state.
- Queue state contains persisted `PaperTask` rows, paper-title lookup, and `DownloadActionUiState`.
- Detail state contains one `PaperUi?`, collections, manifestation task state, and typed detail actions.
- Branch ViewModels own the existing controllers/actions; platform effects remain callbacks/effects in the route.

- [ ] **Step 1: Move More summaries to `MoreViewModel`.**

  Reuse the existing `MoreScreen` summary functions and `PaperPreferenceRow`. The screen receives a
  compact `MoreUiState`; it does not receive the entire library/provider/task objects if only counts
  are needed.

- [ ] **Step 2: Move queue actions and live task state.**

  Observe `logic.tasks.tasks`, keep cancel/retry/remove operations through `logic.downloads` and
  `DownloadWorkScheduler`, and expose acting/failed IDs in one state object. Preserve live aggregate
  progress and back behavior.

- [ ] **Step 3: Move Detail actions.**

  Load the route work ID from `SavedStateHandle` or an explicit route factory, map it to `PaperUi`,
  and retain `ReadablePaperResult`, download, collection, status, repair, and removal semantics.

- [ ] **Step 4: Move branch controllers.**

  Keep backup/import Android document launchers outside ViewModels. The branch ViewModels expose
  preview/confirm/dismiss actions and state; the route performs `ActivityResult` launches and sends
  selected URIs back into the owning ViewModel.

- [ ] **Step 5: Run the complete local host gate and existing connected screen suites.**

  ```powershell
  .\gradlew.bat hostUnitTest hostLint :app:assembleDebug --no-configuration-cache
  .\gradlew.bat :app:connectedDebugAndroidTest -PpaperReaderConnectedTestApplicationIdSuffix=.uitest
  ```

- [ ] **Step 6: Commit the branch migration.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/ui
  git commit -m "refactor(app): isolate branch screen state"
  ```

## Task 5: Remove the app-wide adapter and simplify composition

**Files:**
- Modify: `PaperReaderApp.kt`, `PaperReaderRoutes.kt`, `PaperReaderNavigationParts.kt`
- Delete: `PaperReaderViewModel.kt` after all behavior is migrated
- Modify: ViewModel factory and any tests importing the old class
- Test: existing app unit/connected suites

**Interfaces:**
- `PaperReaderApp` retains global theme/lifecycle/effect responsibilities only.
- Routes construct destination ViewModels through explicit factories and pass stable state/action seams.
- No screen imports `PaperReaderViewModel` after this task.

- [ ] **Step 1: Delete unused root collections and callbacks.**

  Use `rg` to prove no route still needs each removed field before deleting it. Keep incoming intent
  handling and notification/extension lifecycle behavior unchanged.

- [ ] **Step 2: Delete the old adapter.**

  Remove `PaperReaderViewModel.kt` only when `rg -n "PaperReaderViewModel" app/src` returns no
  production references. Retain any reusable controller files with their original behavior.

- [ ] **Step 3: Run boundary/static checks.**

  ```powershell
  .\gradlew.bat hostUnitTest hostLint :app:assembleDebug --no-configuration-cache
  rg -n "Room|OkHttp|Jsoup|IPaper|DAO|PaperReaderViewModel" app/src/main/kotlin/dev/paperreader/app/ui
  ```

  The final search may match allowed type names in comments/resources; production UI must not import
  concrete storage/network/parser/Binder implementations or the deleted root ViewModel.

- [ ] **Step 4: Commit the foundation branch.**

  ```powershell
  git add app/src/main/kotlin/dev/paperreader/app/ui
  git commit -m "refactor(app): adopt screen-level MVVM"
  ```

## Task 6: Foundation review gate

**Files:**
- Modify: none unless a review finding requires a scoped fix
- Test: host unit, lint, APK assembly, and connected app suites

- [ ] **Step 1: Generate the SDD review package from the branch base to HEAD.**
- [ ] **Step 2: Review spec compliance and code quality separately.**
- [ ] **Step 3: Fix only reviewed findings through the implementer/re-review loop.**
- [ ] **Step 4: Record commit range, exact commands, device/API, and deferred work in the SDD ledger.**
