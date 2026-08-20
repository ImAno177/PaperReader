package dev.paperreader.app.ui.theme

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

enum class PaperIconKey {
    ADD,
    BACK,
    BOOKMARK_ADD,
    BOOKMARK_REMOVE,
    BOOKMARKS,
    CLOSE,
    COPY,
    DELETE,
    DONE,
    DOWNLOAD,
    EDIT,
    ERROR,
    FOLDER,
    FORWARD,
    GRID,
    HISTORY,
    INFO,
    LIBRARY,
    LIST,
    MARK_READ,
    MORE_HORIZONTAL,
    MORE_VERTICAL,
    NOTIFICATIONS_OFF,
    NOTIFICATIONS_ON,
    OFFLINE,
    OPEN_EXTERNAL,
    PALETTE,
    PDF,
    PUBLIC,
    SEARCH,
    SORT,
    SYNC,
    UPDATES,
    UPLOAD,
}

enum class PaperIconFamily {
    MATERIAL_SYMBOLS,
    COMMUNITY,
}

@Immutable
data class PaperIconSet(
    val family: PaperIconFamily,
    private val communityPaths: Map<PaperIconKey, String> = emptyMap(),
) {
    init {
        require(family == PaperIconFamily.COMMUNITY || communityPaths.isEmpty())
        require(family != PaperIconFamily.COMMUNITY || communityPaths.keys == PaperIconKey.entries.toSet())
    }

    @DrawableRes
    fun resource(key: PaperIconKey): Int = when (family) {
        PaperIconFamily.MATERIAL_SYMBOLS,
        -> builtinIconResource(family, key)
        PaperIconFamily.COMMUNITY -> error("Community icons are not Android resources")
    }

    fun pathData(key: PaperIconKey): String? = communityPaths[key]

    fun drawable(context: Context, key: PaperIconKey): Drawable =
        pathData(key)?.let { CommunityIconDrawable(it, (24 * context.resources.displayMetrics.density).toInt()) }
            ?: requireNotNull(AppCompatResources.getDrawable(context, resource(key)))

    companion object {
        fun community(paths: Map<PaperIconKey, String>): PaperIconSet =
            PaperIconSet(PaperIconFamily.COMMUNITY, paths.toMap())
    }
}

internal val LocalPaperIcons = staticCompositionLocalOf<PaperIconSet> {
    error("PaperReaderTheme must be applied before reading PaperTheme.icons")
}

val PaperTheme.icons: PaperIconSet
    @Composable
    get() = LocalPaperIcons.current

@Composable
fun PaperIcon(
    key: PaperIconKey,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val iconSet = PaperTheme.icons
    val pathData = iconSet.pathData(key)
    Icon(
        painter = pathData?.let { communityIconPainter(it) } ?: painterResource(iconSet.resource(key)),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
    )
}
