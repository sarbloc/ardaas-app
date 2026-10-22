package io.sarbloc.ardaas.occasion

import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines

// Port of Ardaas/Models/SlotChoice.swift.

/**
 * The "….." slot's two independent choices (#158): the **paath** that was
 * recited, and the **occasion** the Ardaas marks. Either, both or neither.
 *
 * `SavedArdaas` persists the paath as `paathId` — `""` for none, otherwise a
 * bundled entry whose category is [OccasionCategory.PAATH] — and the occasion
 * as it always has, through [OccasionChoice] (`occasionId` +
 * `occasionCustom`). A paath is never free text: "Other…" belongs to the
 * occasion.
 *
 * ## Legacy records and the normaliser
 *
 * Before #158 there was one picker, so a paath was stored in `occasionId`.
 * Those records are read, not migrated: [normalised] applies these rules at
 * read time, so no data migration ever needs the catalog (the Room migration
 * only adds the column).
 *
 * 1. `paathId` empty and `occasionId` naming a catalog entry whose category
 *    is PAATH → that entry **is the paath**, and the occasion is none.
 * 2. `paathId` naming an entry that is not a paath, or one the catalog does
 *    not carry (retired) → **ignored**: no paath, the same way a retired
 *    occasion renders the canonical dots.
 * 3. `paathId` set *and* `occasionId` naming a paath → the occasion is
 *    **ignored** (it cannot be both, and the explicit field wins).
 * 4. Everything else is left exactly as stored — including an occasion id the
 *    catalog has retired, which resolves to no words just as before.
 *
 * The next save writes the normalised form (`paathId` set, `occasionId`
 * cleared), because every writer goes through a draft opened on it.
 */
data class SlotChoice(
    /** `SavedArdaas.paathId`: empty for no paath, else a bundled paath's id. */
    val paathId: String = "",
    val occasion: OccasionChoice = OccasionChoice.Unset,
) {
    /** The rules above, applied against [catalog]. Pure and total; idempotent. */
    fun normalised(catalog: OccasionCatalog): SlotChoice {
        val storedPaath = paathId.trimmingWhitespaceAndNewlines()
        val legacyPaath = (occasion as? OccasionChoice.Catalog)?.id
            ?.takeIf { catalog.occasion(it)?.category == OccasionCategory.PAATH }

        // Rule 1: the pre-#158 shape.
        if (storedPaath.isEmpty()) {
            return if (legacyPaath != null) SlotChoice(legacyPaath, OccasionChoice.Unset) else SlotChoice("", occasion)
        }
        // Rule 2: only a real paath counts as one.
        val paath = if (catalog.occasion(storedPaath)?.category == OccasionCategory.PAATH) storedPaath else ""
        // Rule 3: a paath in the occasion field beside an explicit paath.
        return SlotChoice(paath, if (legacyPaath == null) occasion else OccasionChoice.Unset)
    }

    /**
     * The words for the slot, or null when nothing should be substituted and
     * the dots stay exactly as authored. Normalises first, so a legacy record
     * renders its paath and an ignored id renders nothing. Paath and occasion
     * are joined per layer by [OccasionLayers.Companion.joining].
     */
    fun layers(catalog: OccasionCatalog): OccasionLayers? {
        val normal = normalised(catalog)
        val paath = catalog.occasion(normal.paathId)?.let {
            OccasionLayers(gurmukhi = it.gurmukhi, transliteration = it.transliteration, english = it.english)
        }
        return OccasionLayers.joining(paath = paath, occasion = normal.occasion.layers(catalog), joiner = catalog.joiner)
    }
}

/**
 * One slot phrase from a paath and an occasion, layer by layer (#158):
 *
 * * neither → null (the dots, untouched);
 * * one → exactly that one, so a single choice reads as it always has;
 * * both → `<paath> <joiner> <occasion>`, e.g. `ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ ਜਨਮ
 *   ਦਿਹਾੜੇ`.
 *
 * Per layer, because free text fills only one script
 * ([OccasionLayers.fromFreeText]): where the occasion has no words for a
 * layer, that layer reads the paath alone, and vice versa. A missing joiner —
 * or one with an empty layer — falls back to the paath alone rather than
 * gluing two phrases together or failing.
 */
fun OccasionLayers.Companion.joining(
    paath: OccasionLayers?,
    occasion: OccasionLayers?,
    joiner: SlotJoiner?,
): OccasionLayers? {
    if (paath == null || paath.isEmpty) return occasion?.takeUnless { it.isEmpty }
    if (occasion == null || occasion.isEmpty) return paath
    fun join(first: String, second: String, word: String?): String {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val trimmed = word?.trimmingWhitespaceAndNewlines().orEmpty()
        return if (trimmed.isEmpty()) first else "$first $trimmed $second"
    }
    return OccasionLayers(
        gurmukhi = join(paath.gurmukhi, occasion.gurmukhi, joiner?.gurmukhi),
        transliteration = join(paath.transliteration, occasion.transliteration, joiner?.transliteration),
        english = join(paath.english, occasion.english, joiner?.english),
    )
}
