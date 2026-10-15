package io.sarbloc.ardaas.composer

import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.LayerKind

// Port of Ardaas/Models/LayerVisibility.swift. Keep the two in step: these
// rules decide which words a reader sees and which controls they are offered.

/**
 * The Reader's three layer toggles as one value, so the visibility rules can
 * be written (and tested) as pure functions of the toggle state.
 */
data class LayerToggles(
    val gurmukhi: Boolean,
    val transliteration: Boolean,
    val english: Boolean,
) {
    operator fun get(kind: LayerKind): Boolean = when (kind) {
        LayerKind.GURMUKHI -> gurmukhi
        LayerKind.TRANSLITERATION -> transliteration
        LayerKind.ENGLISH -> english
    }

    fun setting(kind: LayerKind, value: Boolean): LayerToggles = when (kind) {
        LayerKind.GURMUKHI -> copy(gurmukhi = value)
        LayerKind.TRANSLITERATION -> copy(transliteration = value)
        LayerKind.ENGLISH -> copy(english = value)
    }
}

/**
 * One line of the benti as it should be rendered: which layer it is (the
 * Reader styles each differently) and the text to show. A benti contributes
 * at most one line per layer, so [kind] is a stable identity.
 */
data class BentiLine(val kind: LayerKind, val text: String)

/**
 * The benti's lines to render, in layer order, under the Reader's layer
 * toggles.
 *
 * Rules:
 * 1. Only *populated* layers are candidates — an empty layer means "not
 *    written yet" (see [BentiLayers]), so a benti awaiting translation shows
 *    only what exists.
 * 2. A candidate renders when its toggle is on.
 * 3. Never blank: if the toggles would leave a benti that *has* content with
 *    nothing to show, the first populated layer renders anyway — the same
 *    principle as the canonical segments' guard. A visible-but-empty benti
 *    card is worse than an unrequested layer, and dropping the card would
 *    delete the user's own words over a display preference.
 *
 * Deliberately takes no variant coverage: the benti is the user's own words,
 * which carry no attestation question and must never be hidden by one
 * (operator decision, #45). [LayerAvailability.screen] keeps that layer's
 * toggle reachable.
 *
 * An all-blank benti yields no lines; the composer never emits one, so the
 * Reader never draws an empty card.
 */
fun BentiLayers.visibleLines(toggles: LayerToggles): List<BentiLine> {
    val populated = listOf(
        BentiLine(LayerKind.GURMUKHI, gurmukhi),
        BentiLine(LayerKind.TRANSLITERATION, transliteration),
        BentiLine(LayerKind.ENGLISH, english),
    ).filter { it.text.isNotEmpty() }

    val toggledOn = populated.filter { toggles[it.kind] }
    if (toggledOn.isNotEmpty()) return toggledOn
    return listOfNotNull(populated.firstOrNull())
}

/**
 * Whether switching [kind] off would actually remove it from the benti. It
 * would not when the never-blank fallback puts it straight back — an
 * untranslated benti, whose single written layer is all there is to show.
 * Comparing the two renderings keeps this in step with [visibleLines] by
 * construction.
 */
fun BentiLayers.hidesLayer(kind: LayerKind, toggles: LayerToggles): Boolean {
    val shown = visibleLines(toggles).any { it.kind == kind }
    val afterHiding = visibleLines(toggles.setting(kind, false)).any { it.kind == kind }
    return shown && !afterHiding
}

/**
 * Which of the two optional layers can appear at all, for some part of the
 * Reader. Gurmukhi is not modelled as optional: every variant carries it.
 *
 * Two scopes, because the variant and the benti can disagree about a layer:
 * - [canonical] — what the scripture carries; the segments' never-blank
 *   guard has to reason about this alone, or a variant without English would
 *   render blank segments while the benti still showed its own.
 * - [screen] — the union with the benti's populated layers; this drives the
 *   layer *controls*, so a layer only the benti carries is still switchable
 *   and a layer nothing carries can't be the "last visible" one.
 */
data class LayerAvailability(val transliteration: Boolean, val english: Boolean) {

    fun carries(kind: LayerKind): Boolean = when (kind) {
        LayerKind.GURMUKHI -> true
        LayerKind.TRANSLITERATION -> transliteration
        LayerKind.ENGLISH -> english
    }

    /** Layers visible in this scope: toggled on *and* available. */
    fun visibleLayerCount(toggles: LayerToggles): Int = LayerKind.entries.count { toggles[it] && carries(it) }

    /**
     * Which layers a canonical segment renders under these toggles — the
     * toggled-on layers this scope carries, and Gurmukhi alone if that leaves
     * nothing (the segments' never-blank guard, kept here so the segment rows
     * and the toggle-pinning rules can't drift apart).
     */
    fun renderedLayers(toggles: LayerToggles): Set<LayerKind> {
        val rendered = LayerKind.entries.filter { toggles[it] && carries(it) }
        return if (rendered.isEmpty()) setOf(LayerKind.GURMUKHI) else rendered.toSet()
    }

    /**
     * Whether switching [kind] off would actually remove it from the
     * canonical segments. It would not when the never-blank guard puts
     * Gurmukhi straight back — on a Gurmukhi-only variant that makes the
     * Gurmukhi toggle a no-op, so the Reader disables it.
     */
    fun hidesLayer(kind: LayerKind, toggles: LayerToggles): Boolean =
        kind in renderedLayers(toggles) && kind !in renderedLayers(toggles.setting(kind, false))

    companion object {
        /**
         * The variant's own coverage. A null [content] is the pre-load frame:
         * assume full coverage so no toggle is disabled before we know.
         */
        fun canonical(content: ArdaasContent?): LayerAvailability = LayerAvailability(
            transliteration = content?.hasTransliteration ?: true,
            english = content?.hasEnglish ?: true,
        )

        /**
         * The variant's coverage ∪ the benti's populated layers — everything
         * the screen can show. Because the benti renders its English
         * regardless of the variant's attestation (#45), the English control
         * must be reachable regardless too.
         */
        fun screen(content: ArdaasContent?, benti: BentiLayers): LayerAvailability {
            val variant = canonical(content)
            return LayerAvailability(
                transliteration = variant.transliteration || benti.transliteration.isNotEmpty(),
                english = variant.english || benti.english.isNotEmpty(),
            )
        }
    }
}
