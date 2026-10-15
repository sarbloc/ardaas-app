package io.sarbloc.ardaas.content

// Shared test fixtures — the Kotlin counterparts of the `.fixture(...)`
// extensions at the foot of ArdaasTests/ArdaasContentTests.swift, which every
// iOS test file uses.

internal fun fixtureSegment(id: String) = ArdaasSegment(id, "ਗ", transliteration = "g", english = "e")

/** The real marker for Gurmukhi, nothing for the other layers unless asked. */
internal fun fixtureSlot(
    inSegmentId: String,
    gurmukhi: String = "…..",
    transliteration: String? = null,
    english: String? = null,
) = OccasionSlot(inSegmentId, OccasionPlaceholder(gurmukhi, transliteration, english))

internal fun fixtureContent(
    segments: List<ArdaasSegment>,
    slotAfter: String,
    occasionSlot: OccasionSlot? = null,
) = ArdaasContent(1, listOf("test"), segments, BentiSlot(slotAfter), occasionSlot)
