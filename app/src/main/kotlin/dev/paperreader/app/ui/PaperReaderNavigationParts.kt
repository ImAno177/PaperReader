package dev.paperreader.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import dev.paperreader.app.R
import dev.paperreader.app.extensions.CommunityThemeCatalog
import dev.paperreader.app.extensions.CommunityThemeExtensionManager
import dev.paperreader.app.extensions.ExtensionInstallState
import dev.paperreader.app.importer.IncomingPaperReferencePayload
import dev.paperreader.app.importer.IncomingPaperReferenceRequest
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.model.MetadataBackupOperation
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.plugin.ExtensionStoreRegistryState
import dev.paperreader.logic.plugin.VerifiedExtensionRelease

internal object AppRoutes {
    const val LIBRARY = "library"
    const val DISCOVER = "discover"
    const val UPDATES = "updates"
    const val HISTORY = "history"
    const val MORE = "more"
    const val MORE_APPEARANCE = "more/appearance"
    const val MORE_COLLECTIONS = "more/collections"
    const val MORE_READING_IMPORTS = "more/reading-imports"
    const val MORE_UPDATES = "more/updates"
    const val MORE_DATA_BACKUP = "more/data-backup"
    const val MORE_SOURCES = "more/sources"
    const val MORE_ABOUT = "more/about"
    const val DETAIL = "detail/{workId}"
    fun detail(workId: String): String = "detail/${Uri.encode(workId)}"
}

internal data class ExtensionStoreBindings(
    val state: ExtensionStoreRegistryState,
    val action: ExtensionStoreActionUiState,
    val onPreview: (String, String) -> Unit,
    val onConfirm: () -> Unit,
    val onDismissAction: () -> Unit,
    val onRefresh: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val installStates: Map<String, ExtensionInstallState>,
    val onInstallExtension: (VerifiedExtensionRelease) -> Unit,
    val onDismissInstallState: (String) -> Unit,
)

internal fun isMoreRoute(route: String?): Boolean =
    route == AppRoutes.MORE || route?.startsWith("${AppRoutes.MORE}/") == true

internal fun shouldShowPrimaryNavigation(route: String?): Boolean =
    route != AppRoutes.DETAIL && route?.startsWith("${AppRoutes.MORE}/") != true

internal const val PRIMARY_NAVIGATION_TEST_TAG = "primary-navigation"
internal const val PRIMARY_NAVIGATION_ITEM_HIT_TEST_TAG_PREFIX = "primary-navigation-hit-"
internal const val PRIMARY_NAVIGATION_ITEM_VISUAL_TEST_TAG_PREFIX = "primary-navigation-visual-"

internal fun isDestinationSelected(destination: AppDestination, currentRoute: String?): Boolean =
    if (destination == AppDestination.MORE) isMoreRoute(currentRoute) else currentRoute == destination.route

internal enum class AppDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: PaperIconKey,
) {
    LIBRARY(AppRoutes.LIBRARY, R.string.nav_library, PaperIconKey.LIBRARY),
    DISCOVER(AppRoutes.DISCOVER, R.string.nav_discover, PaperIconKey.SEARCH),
    UPDATES(AppRoutes.UPDATES, R.string.nav_updates, PaperIconKey.UPDATES),
    HISTORY(AppRoutes.HISTORY, R.string.nav_history, PaperIconKey.HISTORY),
    MORE(AppRoutes.MORE, R.string.nav_more, PaperIconKey.MORE_HORIZONTAL),
}

@Composable
internal fun IncomingPaperReferenceEffect(
    request: IncomingPaperReferenceRequest?,
    onNavigateToDiscover: () -> Unit,
    onSearch: (String) -> Unit,
    onInvalid: () -> Unit,
    onConsumed: (Long) -> Unit,
) {
    LaunchedEffect(request) {
        val current = request ?: return@LaunchedEffect
        when (val payload = current.payload) {
            is IncomingPaperReferencePayload.Valid -> {
                onNavigateToDiscover()
                onSearch(payload.reference.query)
            }
            IncomingPaperReferencePayload.Invalid -> onInvalid()
        }
        onConsumed(current.id)
    }
}

internal fun shouldOpenMoreForLocalPdfImport(state: LocalPdfImportUiState): Boolean = when (state) {
    LocalPdfImportUiState.Idle,
    LocalPdfImportUiState.Preparing,
    -> false
    is LocalPdfImportUiState.Confirming,
    is LocalPdfImportUiState.Importing,
    is LocalPdfImportUiState.Complete,
    is LocalPdfImportUiState.Failed,
    -> true
}

internal enum class MoreAttentionDestination { DATA_BACKUP, READING_IMPORTS }

internal fun moreAttentionDestination(
    metadataBackup: MetadataBackupUiState,
    localPdfImport: LocalPdfImportUiState,
): MoreAttentionDestination? {
    val restoreNeedsAttention = metadataBackup is MetadataBackupUiState.Preview ||
        metadataBackup is MetadataBackupUiState.Restoring ||
        (metadataBackup is MetadataBackupUiState.Failed && metadataBackup.operation == MetadataBackupOperation.PREVIEW)
    return when {
        restoreNeedsAttention -> MoreAttentionDestination.DATA_BACKUP
        shouldOpenMoreForLocalPdfImport(localPdfImport) -> MoreAttentionDestination.READING_IMPORTS
        else -> null
    }
}

internal fun NavHostController.navigateToMoreBranch(route: String) {
    val currentRoute = currentDestination?.route
    if (currentRoute == route) return
    if (!isMoreRoute(currentRoute)) navigate(AppRoutes.MORE) { launchSingleTop = true }
    navigate(route) {
        popUpTo(AppRoutes.MORE) { inclusive = false }
        launchSingleTop = true
    }
}

@Composable
internal fun AdaptiveAppShell(
    destinations: List<AppDestination>,
    currentRoute: String?,
    onNavigate: (AppDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(PaperTheme.tokens.canvas)) {
        val showLabels = LocalDensity.current.fontScale < 1.5f
        if (maxWidth < 600.dp) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = PaperTheme.tokens.canvas,
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.testTag(PRIMARY_NAVIGATION_TEST_TAG).padding(horizontal = 12.dp),
                        containerColor = PaperTheme.tokens.canvas,
                        contentColor = PaperTheme.tokens.ink,
                        tonalElevation = 0.dp,
                    ) {
                        destinations.forEach { destination ->
                            PaperDestinationItem(
                                modifier = Modifier.weight(1f),
                                selected = isDestinationSelected(destination, currentRoute),
                                onClick = { onNavigate(destination) },
                                icon = destination.icon,
                                label = stringResource(destination.labelRes),
                                testKey = destination.route,
                                showLabel = showLabels,
                            )
                        }
                    }
                },
            ) { padding -> content(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) }
        } else {
            val compactRail = maxHeight < 480.dp
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.testTag(PRIMARY_NAVIGATION_TEST_TAG),
                    containerColor = PaperTheme.tokens.canvas,
                    contentColor = PaperTheme.tokens.ink,
                    windowInsets = NavigationRailDefaults.windowInsets,
                ) {
                    destinations.forEach { destination ->
                        PaperDestinationItem(
                            modifier = Modifier.padding(vertical = if (compactRail) 2.dp else 4.dp).width(80.dp),
                            selected = isDestinationSelected(destination, currentRoute),
                            onClick = { onNavigate(destination) },
                            icon = destination.icon,
                            label = stringResource(destination.labelRes),
                            testKey = destination.route,
                            itemHeight = if (compactRail) 56.dp else 72.dp,
                            visualPadding = PaddingValues(if (compactRail) 2.dp else 4.dp),
                            showLabel = showLabels,
                        )
                    }
                }
                content(Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@Composable
private fun PaperDestinationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: PaperIconKey,
    label: String,
    modifier: Modifier = Modifier,
    testKey: String,
    itemHeight: Dp = 72.dp,
    visualPadding: PaddingValues = PaddingValues(4.dp),
    showLabel: Boolean = true,
) {
    val tokens = PaperTheme.tokens
    val borderWidth by animateDpAsState(
        targetValue = if (selected) tokens.borderWidth.coerceAtLeast(1.dp) else 0.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "primary navigation border width",
    )
    val borderColor = if (selected) tokens.border else Color.Transparent
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "primary navigation icon scale",
    )
    Box(
        modifier = modifier.height(itemHeight)
            .testTag("$PRIMARY_NAVIGATION_ITEM_HIT_TEST_TAG_PREFIX$testKey")
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(visualPadding)
                .testTag("$PRIMARY_NAVIGATION_ITEM_VISUAL_TEST_TAG_PREFIX$testKey"),
            shape = RoundedCornerShape(tokens.cornerRadius),
            color = Color.Transparent,
            contentColor = tokens.ink,
            border = BorderStroke(borderWidth, borderColor).takeIf { borderWidth > 0.dp },
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 1.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                PaperIcon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                )
                if (showLabel) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
