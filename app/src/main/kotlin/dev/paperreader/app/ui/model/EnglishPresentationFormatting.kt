package dev.paperreader.app.ui.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun LocalDate.toEnglishDisplayDate(): String = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(Locale.ENGLISH)
    .format(this)

fun Instant.toEnglishDisplayDateTime(zoneId: ZoneId = ZoneId.systemDefault()): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withLocale(Locale.ENGLISH)
    .format(atZone(zoneId))
