package io.sarbloc.ardaas.ui.home

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A row's save date as day, month and year in the reader's locale and time
 * zone — iOS's `.dateTime.day().month().year()`, e.g. "14 Mar 2026" (UK) or
 * "Mar 14, 2026" (US). No time of day, as on iOS.
 */
fun formatSavedDate(instant: Instant, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withZone(zone)
        .format(instant)
