package io.sarbloc.ardaas.ui.home

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedDateFormatTest {

    private val instant = Instant.parse("2026-03-14T23:30:00Z")

    @Test
    fun dayMonthYearInTheReadersLocale() {
        assertEquals("14 Mar 2026", formatSavedDate(instant, ZoneOffset.UTC, Locale.UK))
        assertEquals("Mar 14, 2026", formatSavedDate(instant, ZoneOffset.UTC, Locale.US))
    }

    @Test
    fun theDateIsTheReadersLocalDay() {
        // 23:30 UTC is already the next morning in Amritsar.
        assertEquals("15 Mar 2026", formatSavedDate(instant, ZoneId.of("Asia/Kolkata"), Locale.UK))
    }
}
