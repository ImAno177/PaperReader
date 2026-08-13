package dev.paperreader.app.ui.theme

import dev.paperreader.extensions.api.ThemePalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal fun ThemePalette.accessibilityContrastFailures(): List<String> {
    val pairs = listOf(
        "ink/canvas" to (ink to canvas),
        "ink/surface" to (ink to surface),
        "inkMuted/canvas" to (inkMuted to canvas),
        "inkMuted/surface" to (inkMuted to surface),
        "onPrimary/primary" to (onPrimary to primary),
        "onSecondary/secondary" to (onSecondary to secondary),
        "onPrimaryContainer/primaryContainer" to (onPrimaryContainer to primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (onSecondaryContainer to secondaryContainer),
        "emptyStateAccent/canvas" to (emptyStateAccent to canvas),
        "success/canvas" to (success to canvas),
        "success/surface" to (success to surface),
        "warning/canvas" to (warning to canvas),
        "warning/surface" to (warning to surface),
        "danger/canvas" to (danger to canvas),
        "danger/surface" to (danger to surface),
    )
    return pairs.mapNotNull { (role, colors) ->
        role.takeIf { contrastRatio(colors.first, colors.second) < WCAG_AA_NORMAL_TEXT }
    }
}

private fun contrastRatio(first: Int, second: Int): Double {
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    return (max(firstLuminance, secondLuminance) + 0.05) /
        (min(firstLuminance, secondLuminance) + 0.05)
}

private fun relativeLuminance(argb: Int): Double =
    0.2126 * channel(argb, 16).linearized() +
        0.7152 * channel(argb, 8).linearized() +
        0.0722 * channel(argb, 0).linearized()

private fun channel(argb: Int, shift: Int): Double = ((argb ushr shift) and 0xff) / 255.0

private fun Double.linearized(): Double =
    if (this <= 0.04045) this / 12.92 else ((this + 0.055) / 1.055).pow(2.4)

private const val WCAG_AA_NORMAL_TEXT = 4.5
