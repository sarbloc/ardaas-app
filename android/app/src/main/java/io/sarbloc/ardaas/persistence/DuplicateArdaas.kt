package io.sarbloc.ardaas.persistence

import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines
import java.text.Normalizer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

// Port of Ardaas/Models/DuplicateLabel.swift (#159).

/**
 * The label a duplicated Ardaas is saved with: "X" copies to "X copy", then
 * "X copy 2", "X copy 3" — the smallest number from 2 that no saved label
 * already uses.
 *
 * Duplicating a copy counts from the original rather than stacking suffixes:
 * a trailing " copy" or " copy N" is stripped to find the base, so "X copy"
 * copies to "X copy 2", never "X copy copy".
 *
 * Labels are compared trimmed (iOS's whitespace set), case-insensitively and
 * up to canonical equivalence, as a reader would see them: "family COPY "
 * already takes "Family copy", and a precomposed ਖ਼ (U+0A59) takes the same
 * label as ਖ + nukta — Swift's `String` equality, which iOS gets for free.
 * Compose enforces no maximum label length, so nothing is truncated.
 *
 * Pure, and asserted case for case by both apps through the shared
 * `ArdaasTests/Fixtures/duplicate-label-parity.json`.
 */
object DuplicateLabel {
    /**
     * The word the copy is marked with, as typed into the label: data, not
     * UI text, so it is not a string resource (the rest of the label is the
     * user's, and iOS spells it the same).
     */
    const val MARKER = "copy"

    private const val SUFFIX = " $MARKER"

    /** The label for a copy of a record labelled [label], given every label already saved ([label]'s own included). */
    fun forCopyOf(label: String, existing: Collection<String>): String {
        val base = base(label)
        val taken = existing.mapTo(HashSet(), ::key)
        val first = joined(base, MARKER)
        if (key(first) !in taken) return first
        var number = 2
        while (key(joined(base, "$MARKER $number")) in taken) number++
        return joined(base, "$MARKER $number")
    }

    /**
     * [label] trimmed, without a trailing " copy" or " copy N" (ASCII digits,
     * any number of them; matched case-insensitively). A label that is
     * nothing but the suffix keeps it — "copy 2" is a base, not a copy of
     * nothing.
     */
    fun base(label: String): String {
        val trimmed = label.trimmingWhitespaceAndNewlines()
        var rest = trimmed
        // 0–9 only: a Gurmukhi or other numeral is part of the label.
        val digitCount = rest.takeLastWhile { it in '0'..'9' }.length
        if (digitCount > 0) {
            rest = rest.dropLast(digitCount)
            if (!rest.endsWith(' ')) return trimmed
            rest = rest.dropLast(1)
        }
        if (rest.length <= SUFFIX.length || rest.takeLast(SUFFIX.length).lowercase(Locale.ROOT) != SUFFIX) {
            return trimmed
        }
        val base = rest.dropLast(SUFFIX.length).trimmingWhitespaceAndNewlines()
        return base.ifEmpty { trimmed }
    }

    /**
     * How two labels are compared: trimmed, case-insensitively, and NFC
     * normalised so canonically equivalent spellings collide, as they do in
     * a Swift `Set<String>`. Only the key is normalised; the label saved is
     * the user's own text.
     */
    fun key(label: String): String =
        Normalizer.normalize(label.trimmingWhitespaceAndNewlines().lowercase(Locale.ROOT), Normalizer.Form.NFC)

    private fun joined(base: String, suffix: String): String = if (base.isEmpty()) suffix else "$base $suffix"
}

/**
 * A new record (id 0, for Room to assign) holding every user field of this
 * one as stored — variant, all three benti layers, the occasion pair and the
 * paath — under [label], created at [createdAt] so it sorts as new.
 *
 * A data-class `copy`, so a field added to [SavedArdaas] later is carried
 * without touching this; and raw, not through the `occasion` constructor or
 * [withSlotChoice], which normalise — a copy must read exactly as the
 * original does.
 */
fun SavedArdaas.duplicate(
    label: String,
    createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS),
): SavedArdaas = copy(label = label, createdAt = createdAt, id = 0)

