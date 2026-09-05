package dev.paperreader.app.ui.model

import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

enum class PaperDateFormat(
    val storageKey: String,
) {
    DEFAULT("default"),
    US_SHORT("us-short"),
    EUROPEAN("european"),
    ISO("iso"),
    LONG("long"),
    ;

    companion object {
        fun fromStorageKey(value: String?): PaperDateFormat = entries
            .firstOrNull { it.storageKey == value }
            ?: DEFAULT
    }
}

fun LocalDate.toEnglishDisplayDate(): String = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(Locale.ENGLISH)
    .format(this)

fun Instant.toEnglishDisplayDateTime(zoneId: ZoneId = ZoneId.systemDefault()): String =
    toEnglishDisplayDateTime(PaperDateFormat.DEFAULT, zoneId)

fun Instant.toEnglishDisplayDateTime(
    format: PaperDateFormat,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val formatter = when (format) {
        PaperDateFormat.DEFAULT -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        PaperDateFormat.US_SHORT -> DateTimeFormatter.ofPattern("MM/dd/yy HH:mm", Locale.ENGLISH)
        PaperDateFormat.EUROPEAN -> DateTimeFormatter.ofPattern("dd/MM/yy HH:mm", Locale.ENGLISH)
        PaperDateFormat.ISO -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
        PaperDateFormat.LONG -> DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH)
    }.withLocale(Locale.ENGLISH)
    return formatter.format(atZone(zoneId))
}

fun Instant.toEnglishRelativeTime(now: Instant = Instant.now()): String {
    val seconds = Duration.between(this, now).seconds
    val future = seconds < 0
    val magnitude = abs(seconds)
    if (magnitude < 60) return "just now"
    val (amount, unit) = when {
        magnitude < 60 * 60 -> magnitude / 60 to "minute"
        magnitude < 24 * 60 * 60 -> magnitude / (60 * 60) to "hour"
        magnitude < 7 * 24 * 60 * 60 -> magnitude / (24 * 60 * 60) to "day"
        magnitude < 30 * 24 * 60 * 60 -> magnitude / (7 * 24 * 60 * 60) to "week"
        magnitude < 365 * 24 * 60 * 60 -> magnitude / (30 * 24 * 60 * 60) to "month"
        else -> magnitude / (365 * 24 * 60 * 60) to "year"
    }
    val value = "$amount $unit${if (amount == 1L) "" else "s"}"
    return if (future) "in $value" else "$value ago"
}
