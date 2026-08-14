package dev.paperreader.extensions.api

import android.os.Bundle

enum class ThemeSemanticIcon(val wireValue: String) {
    ADD("add"),
    BACK("back"),
    BOOKMARK_ADD("bookmark_add"),
    BOOKMARK_REMOVE("bookmark_remove"),
    BOOKMARKS("bookmarks"),
    CLOSE("close"),
    COPY("copy"),
    DELETE("delete"),
    DONE("done"),
    DOWNLOAD("download"),
    EDIT("edit"),
    ERROR("error"),
    FOLDER("folder"),
    FORWARD("forward"),
    GRID("grid"),
    HISTORY("history"),
    INFO("info"),
    LIBRARY("library"),
    LIST("list"),
    MARK_READ("mark_read"),
    MORE_HORIZONTAL("more_horizontal"),
    MORE_VERTICAL("more_vertical"),
    NOTIFICATIONS_OFF("notifications_off"),
    NOTIFICATIONS_ON("notifications_on"),
    OFFLINE("offline"),
    OPEN_EXTERNAL("open_external"),
    PALETTE("palette"),
    PDF("pdf"),
    PUBLIC("public"),
    SEARCH("search"),
    SORT("sort"),
    SYNC("sync"),
    UPDATES("updates"),
    UPLOAD("upload"),
}

enum class ThemeFontFamily(val wireValue: String) {
    SYSTEM_SANS("system_sans"),
    SYSTEM_SERIF("system_serif"),
    SYSTEM_MONOSPACE("system_monospace"),
}

enum class ThemeDecoration(val wireValue: String) {
    NONE("none"),
    DOODLE("doodle"),
    /** Kept for already-published community themes; never exposed as a built-in preset. */
    RETRO_GRID("retro_grid"),
}

data class ThemeExtensionDescriptor(
    val packageName: String,
    val displayName: String,
    val themeIds: Set<String>,
    val apiVersion: Int = PaperExtensionContract.API_VERSION,
) {
    init {
        require(packageName.contains('.') && packageName.length <= 255)
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(themeIds.isNotEmpty() && themeIds.size <= 20)
        require(themeIds.all(::isValidThemeId))
        require(apiVersion == PaperExtensionContract.API_VERSION)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.PACKAGE_NAME, packageName)
        putString(Keys.DISPLAY_NAME, displayName)
        putStringArrayList(Keys.THEMES, ArrayList(themeIds.sorted()))
        putInt(Keys.API_VERSION, apiVersion)
    }

    companion object {
        fun fromBundle(bundle: Bundle): ThemeExtensionDescriptor = ThemeExtensionDescriptor(
            packageName = bundle.requiredString(Keys.PACKAGE_NAME),
            displayName = bundle.requiredString(Keys.DISPLAY_NAME),
            themeIds = bundle.getStringArrayList(Keys.THEMES).orEmpty().toSet(),
            apiVersion = bundle.getInt(Keys.API_VERSION, -1),
        )
    }
}

data class ThemePalette(
    val canvas: Int,
    val surface: Int,
    val surfaceMuted: Int,
    val ink: Int,
    val inkMuted: Int,
    val border: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val success: Int,
    val warning: Int,
    val danger: Int,
    val emptyStateAccent: Int,
    val selection: Int,
    val hardShadow: Int,
) {
    init {
        allColors().forEach { color ->
            require(color ushr 24 == 0xff) { "Theme colors must be opaque ARGB values" }
        }
    }

    internal fun toBundle(): Bundle = Bundle().apply {
        COLOR_KEYS.zip(allColors()).forEach { (key, color) -> putInt(key, color) }
    }

    private fun allColors(): List<Int> = listOf(
        canvas,
        surface,
        surfaceMuted,
        ink,
        inkMuted,
        border,
        primary,
        onPrimary,
        primaryContainer,
        onPrimaryContainer,
        secondary,
        onSecondary,
        secondaryContainer,
        onSecondaryContainer,
        success,
        warning,
        danger,
        emptyStateAccent,
        selection,
        hardShadow,
    )

    companion object {
        internal fun fromBundle(bundle: Bundle): ThemePalette {
            COLOR_KEYS.forEach { key -> require(bundle.containsKey(key)) { "Missing theme color: $key" } }
            val colors = COLOR_KEYS.map(bundle::getInt)
            return ThemePalette(
                canvas = colors[0],
                surface = colors[1],
                surfaceMuted = colors[2],
                ink = colors[3],
                inkMuted = colors[4],
                border = colors[5],
                primary = colors[6],
                onPrimary = colors[7],
                primaryContainer = colors[8],
                onPrimaryContainer = colors[9],
                secondary = colors[10],
                onSecondary = colors[11],
                secondaryContainer = colors[12],
                onSecondaryContainer = colors[13],
                success = colors[14],
                warning = colors[15],
                danger = colors[16],
                emptyStateAccent = colors[17],
                selection = colors[18],
                hardShadow = colors[19],
            )
        }
    }
}

data class CommunityTheme(
    val requestId: String,
    val themeId: String,
    val displayName: String,
    val lightPalette: ThemePalette,
    val darkPalette: ThemePalette,
    val cornerRadiusDp: Float,
    val borderWidthDp: Float,
    val shadowOffsetDp: Float,
    val titleFont: ThemeFontFamily,
    val bodyFont: ThemeFontFamily,
    val labelFont: ThemeFontFamily,
    val decoration: ThemeDecoration,
    val iconKeys: Set<ThemeSemanticIcon>,
) {
    init {
        requireValidRequestId(requestId)
        require(isValidThemeId(themeId))
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(cornerRadiusDp in 0f..32f)
        require(borderWidthDp in 0f..4f)
        require(shadowOffsetDp in 0f..8f)
        require(iconKeys == ThemeSemanticIcon.entries.toSet()) {
            "A community theme must provide every semantic icon"
        }
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.REQUEST_ID, requestId)
        putString(Keys.THEME_ID, themeId)
        putString(Keys.DISPLAY_NAME, displayName)
        putBundle(Keys.LIGHT_PALETTE, lightPalette.toBundle())
        putBundle(Keys.DARK_PALETTE, darkPalette.toBundle())
        putFloat(Keys.CORNER_RADIUS_DP, cornerRadiusDp)
        putFloat(Keys.BORDER_WIDTH_DP, borderWidthDp)
        putFloat(Keys.SHADOW_OFFSET_DP, shadowOffsetDp)
        putString(Keys.TITLE_FONT, titleFont.wireValue)
        putString(Keys.BODY_FONT, bodyFont.wireValue)
        putString(Keys.LABEL_FONT, labelFont.wireValue)
        putString(Keys.DECORATION, decoration.wireValue)
        putStringArrayList(Keys.ICON_KEYS, ArrayList(iconKeys.map(ThemeSemanticIcon::wireValue).sorted()))
    }.also(ExtensionPayloadValidator::requireBinderSafe)

    companion object {
        fun fromBundle(bundle: Bundle): CommunityTheme {
            ExtensionPayloadValidator.requireBinderSafe(bundle)
            return CommunityTheme(
                requestId = bundle.requiredString(Keys.REQUEST_ID),
                themeId = bundle.requiredString(Keys.THEME_ID),
                displayName = bundle.requiredString(Keys.DISPLAY_NAME),
                lightPalette = ThemePalette.fromBundle(requireNotNull(bundle.getBundle(Keys.LIGHT_PALETTE))),
                darkPalette = ThemePalette.fromBundle(requireNotNull(bundle.getBundle(Keys.DARK_PALETTE))),
                cornerRadiusDp = bundle.getFloat(Keys.CORNER_RADIUS_DP, -1f),
                borderWidthDp = bundle.getFloat(Keys.BORDER_WIDTH_DP, -1f),
                shadowOffsetDp = bundle.getFloat(Keys.SHADOW_OFFSET_DP, -1f),
                titleFont = parseFont(bundle.requiredString(Keys.TITLE_FONT)),
                bodyFont = parseFont(bundle.requiredString(Keys.BODY_FONT)),
                labelFont = parseFont(bundle.requiredString(Keys.LABEL_FONT)),
                decoration = parseDecoration(bundle.requiredString(Keys.DECORATION)),
                iconKeys = bundle.getStringArrayList(Keys.ICON_KEYS)
                    .orEmpty()
                    .mapTo(linkedSetOf(), ::parseIcon),
            )
        }

        private fun parseFont(value: String): ThemeFontFamily =
            requireNotNull(ThemeFontFamily.entries.firstOrNull { it.wireValue == value }) {
                "Unknown theme font family"
            }

        private fun parseDecoration(value: String): ThemeDecoration =
            requireNotNull(ThemeDecoration.entries.firstOrNull { it.wireValue == value }) {
                "Unknown theme decoration"
            }

        private fun parseIcon(value: String): ThemeSemanticIcon =
            requireNotNull(ThemeSemanticIcon.entries.firstOrNull { it.wireValue == value }) {
                "Unknown semantic icon"
            }
    }
}

fun requireValidIconPathData(bytes: ByteArray): String {
    require(bytes.isNotEmpty() && bytes.size <= PaperExtensionContract.MAX_ICON_BYTES)
    val value = bytes.toString(Charsets.US_ASCII).trim()
    require(value.isNotEmpty())
    require(value.all { it.isDigit() || it in "MmLlHhVvCcSsQqTtAaZzEe+-. ,\n\r\t" }) {
        "Icon contains unsupported path data"
    }
    return value
}

private fun isValidThemeId(value: String): Boolean =
    value.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}"))

private val COLOR_KEYS = listOf(
    "canvas",
    "surface",
    "surface_muted",
    "ink",
    "ink_muted",
    "border",
    "primary",
    "on_primary",
    "primary_container",
    "on_primary_container",
    "secondary",
    "on_secondary",
    "secondary_container",
    "on_secondary_container",
    "success",
    "warning",
    "danger",
    "empty_state_accent",
    "selection",
    "hard_shadow",
)
