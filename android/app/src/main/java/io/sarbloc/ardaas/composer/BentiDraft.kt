package io.sarbloc.ardaas.composer

import io.sarbloc.ardaas.text.BentiScript
import io.sarbloc.ardaas.text.BentiScriptDetector
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines
import io.sarbloc.ardaas.transliteration.GurmukhiTransliterator

// Port of Ardaas/Models/BentiDraft.swift, minus the translation engine: v1
// Android has no translator, so iOS's `translatedFrom` / `isStale` /
// `isTranslatable` / `translationSource` / `applyTranslation` have nothing to
// track. What iOS fills by machine translation, the user types here directly
// into the Gurmukhi editor; every layer rule is unchanged.

/**
 * The Compose screen's benti as pure, testable data: what the user typed in
 * the benti editor, what they typed in the Gurmukhi editor, and the rules that
 * turn the two into the three layers persisted on `SavedArdaas`.
 *
 * The screen binds its editors straight to [typed] and [gurmukhi]. Everything
 * else — the detected script, the transliteration, the layers — is
 * *derived*, never stored, so there is no second copy to keep in sync. The
 * transliteration is recomputed from the current Gurmukhi on every read,
 * which is what makes an edit to the Gurmukhi regenerate it for free.
 */
data class BentiDraft(
    /**
     * Exactly what the user typed in the benti editor — English, or Gurmukhi
     * if they used the Punjabi keyboard there.
     */
    val typed: String = "",
    /**
     * The Gurmukhi the user typed in the Gurmukhi editor. Ignored when
     * [typed] is itself Gurmukhi: the typed text is then the Gurmukhi layer,
     * exactly as iOS ignores its translation draft in that case.
     */
    val gurmukhi: String = "",
) {
    val script: BentiScript get() = BentiScriptDetector.detect(typed)

    /** True when the benti editor already holds Gurmukhi, so only the transliteration is generated. */
    val isTypedInGurmukhi: Boolean get() = script == BentiScript.GURMUKHI

    /**
     * The Gurmukhi that will be saved: the typed text when it is already
     * Gurmukhi, otherwise the Gurmukhi editor's text.
     */
    val effectiveGurmukhi: String
        get() = (if (isTypedInGurmukhi) typed else gurmukhi).trimmingWhitespaceAndNewlines()

    /** Regenerated from [effectiveGurmukhi] on every read — deterministic, offline and instant. */
    val transliteration: String
        get() {
            val source = effectiveGurmukhi
            return if (source.isEmpty()) "" else GurmukhiTransliterator.transliterate(source)
        }

    /**
     * The three layers to persist.
     *
     * * Typed in Gurmukhi → Gurmukhi + transliteration; the English layer is
     *   left empty, because nothing here translates Gurmukhi back to English
     *   and putting the Gurmukhi in the English slot would mislabel it.
     * * Typed in anything else → the typed text is the English layer, and the
     *   Gurmukhi / transliteration layers come from the Gurmukhi editor, empty
     *   when nothing was typed there.
     */
    val layers: BentiLayers
        get() = BentiLayers(
            gurmukhi = effectiveGurmukhi,
            transliteration = transliteration,
            english = if (isTypedInGurmukhi) "" else typed,
        )

    /** Nothing worth saving in any layer. */
    val isEmpty: Boolean get() = layers.isEmpty

    /**
     * The Gurmukhi editor holds text that will be saved — iOS's
     * `hasTranslationDraft`. Only meaningful while the benti editor is not
     * itself Gurmukhi.
     */
    val hasGurmukhiEntry: Boolean
        get() = !isTypedInGurmukhi && gurmukhi.trimmingWhitespaceAndNewlines().isNotEmpty()
}
