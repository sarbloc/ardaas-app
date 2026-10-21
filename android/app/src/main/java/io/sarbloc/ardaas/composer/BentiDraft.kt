package io.sarbloc.ardaas.composer

import io.sarbloc.ardaas.text.BentiScript
import io.sarbloc.ardaas.text.BentiScriptDetector
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines
import io.sarbloc.ardaas.transliteration.GurmukhiTransliterator

// Port of Ardaas/Models/BentiDraft.swift, rule for rule. v1 Android shipped
// without a translator and let the user hand-type the Gurmukhi instead; since
// #120 the Gurmukhi editor is iOS's translation draft again — filled by
// [BentiDraft.applyTranslation], then editable — and `translatedFrom` tracks
// staleness exactly as on iOS. Swift's `mutating` functions are copies here,
// because the screen keeps the draft as one immutable saveable value.

/**
 * The Compose screen's benti as pure, testable data: what the user typed, the
 * Gurmukhi draft (if any), and the rules that turn the two into the three
 * layers persisted on `SavedArdaas`.
 *
 * The screen binds its editors straight to [typed] and [gurmukhi]. Everything
 * else — the detected script, the transliteration, the layers, whether the
 * draft has gone stale — is *derived*, never stored, so there is no second
 * copy to keep in sync. In particular the transliteration is recomputed from
 * the current Gurmukhi on every read, which is what makes an edit to the
 * Gurmukhi regenerate it for free (the transliterator is deterministic,
 * offline and needs no model).
 */
data class BentiDraft(
    /**
     * Exactly what the user typed in the benti editor — English, or Gurmukhi
     * if they used the Punjabi keyboard.
     */
    val typed: String = "",
    /**
     * The machine-translated Gurmukhi, after any edits the user made to it.
     * Empty when nothing has been translated. Ignored when [typed] is itself
     * Gurmukhi (there is nothing to translate then, so the typed text is the
     * Gurmukhi layer).
     */
    val gurmukhi: String = "",
    /**
     * The trimmed English that [gurmukhi] was translated from, or null when
     * there is no translation. Used only to notice that the English has been
     * edited since — see [isStale].
     */
    val translatedFrom: String? = null,
    /**
     * The Gurmukhi draft section is open on screen. Set when a translation
     * lands and cleared only by [clearTranslation] ("Remove Gurmukhi") —
     * deliberately *not* derived from [gurmukhi] being non-empty, so emptying
     * the editor mid-edit leaves the editor where it is instead of removing it
     * from under the user (#131). Editor state only: it never reaches
     * [layers], so what gets saved is unchanged. Defaults to whether
     * [gurmukhi] holds anything, so a draft built with Gurmukhi already in it
     * opens its section.
     */
    val isDraftOpen: Boolean = gurmukhi.trimmingWhitespaceAndNewlines().isNotEmpty(),
) {
    val script: BentiScript get() = BentiScriptDetector.detect(typed)

    /**
     * True when the typed text is already Gurmukhi, so translation is skipped
     * and only the transliteration is generated.
     */
    val isTypedInGurmukhi: Boolean get() = script == BentiScript.GURMUKHI

    /**
     * The Gurmukhi that will be saved: the typed text when it is already
     * Gurmukhi, otherwise the translation draft.
     */
    val effectiveGurmukhi: String
        get() = (if (isTypedInGurmukhi) typed else gurmukhi).trimmingWhitespaceAndNewlines()

    /** Regenerated from [effectiveGurmukhi] on every read — deterministic and instant. */
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
     *   Gurmukhi / transliteration layers are filled in only once a
     *   translation exists. **No translation means English only**, exactly as
     *   before Compose could translate.
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
     * There is a translation and the English has changed since it was made,
     * so the Gurmukhi on screen no longer corresponds to the text above it.
     * The draft is kept (it may hold the user's own edits) and flagged, never
     * silently discarded or silently saved as if it still matched.
     *
     * Deleting the English entirely is not staleness: there is then nothing
     * for the Gurmukhi to disagree with, and a Gurmukhi-only benti is a
     * perfectly good thing to save.
     */
    val isStale: Boolean
        get() {
            val source = translatedFrom ?: return false
            if (!hasTranslationDraft) return false
            val current = typed.trimmingWhitespaceAndNewlines()
            return current.isNotEmpty() && current != source
        }

    /**
     * The translation draft holds Gurmukhi that counts: only meaningful while
     * the typed text is not itself Gurmukhi. Drives staleness — an empty
     * draft saves English only, so it has nothing to disagree with.
     */
    val hasTranslationDraft: Boolean
        get() = !isTypedInGurmukhi && gurmukhi.trimmingWhitespaceAndNewlines().isNotEmpty()

    /**
     * The Gurmukhi draft section is on screen: it is open, and the typed text
     * is not itself Gurmukhi (a draft from earlier English stays hidden while
     * it is ignored, and comes back if the English does). Stays true while
     * the editor is empty — see [isDraftOpen].
     */
    val showsTranslationDraft: Boolean
        get() = !isTypedInGurmukhi && isDraftOpen

    /**
     * Text the translator can be given. [BentiScript.UNDETERMINED] (digits,
     * punctuation, emoji) has nothing to translate, and Gurmukhi is already
     * there.
     */
    val isTranslatable: Boolean get() = script == BentiScript.LATIN

    /** The exact string handed to the translator, and recorded as the source of the resulting draft. */
    val translationSource: String get() = typed.trimmingWhitespaceAndNewlines()

    /** Records a fresh translation of [source]. */
    fun applyTranslation(translated: String, source: String): BentiDraft = copy(
        gurmukhi = translated.trimmingWhitespaceAndNewlines(),
        translatedFrom = source.trimmingWhitespaceAndNewlines(),
        isDraftOpen = true,
    )

    /** Drops the translation draft, returning to English-only, and closes its section. */
    fun clearTranslation(): BentiDraft = copy(gurmukhi = "", translatedFrom = null, isDraftOpen = false)

    companion object {
        /**
         * The draft that saves back to [saved] — how Compose reopens a saved
         * benti for editing (#154). The exact inverse of [layers] for every
         * shape [layers] produces:
         *
         * * **English + Gurmukhi** (translated): the English is [typed], the
         *   Gurmukhi is the open translation draft, and [translatedFrom] is
         *   the English, so the pair counts as one translation — not stale on
         *   open, stale once the English changes.
         * * **English only** (never translated, or Remove Gurmukhi): the
         *   English is [typed]; no draft, section closed.
         * * **Gurmukhi only, in Gurmukhi script** (typed with the Punjabi
         *   keyboard, or a translation whose English was then deleted): the
         *   Gurmukhi is [typed] — the benti editor, where it was typed.
         * * **Gurmukhi only, not in Gurmukhi script** (a translation draft
         *   edited into something the detector reads as another script, with
         *   the English deleted): left in the open translation draft under an
         *   empty benti editor, since [typed] would read it as English.
         *
         * The transliteration is never read: it is derived from the Gurmukhi,
         * so it comes back regenerated — identical unless the transliterator
         * has changed since the record was saved.
         *
         * English in Gurmukhi script is the one shape this cannot reproduce,
         * and no version of Compose ever saved it: [layers] only writes the
         * English layer when the typed text is not Gurmukhi.
         */
        fun from(saved: BentiLayers): BentiDraft {
            val english = saved.english
            val gurmukhi = saved.gurmukhi
            return when {
                english.isNotEmpty() && gurmukhi.isNotEmpty() ->
                    BentiDraft(typed = english, gurmukhi = gurmukhi, translatedFrom = english, isDraftOpen = true)
                english.isNotEmpty() -> BentiDraft(typed = english)
                gurmukhi.isEmpty() -> BentiDraft()
                BentiScriptDetector.detect(gurmukhi) == BentiScript.GURMUKHI -> BentiDraft(typed = gurmukhi)
                else -> BentiDraft(gurmukhi = gurmukhi, isDraftOpen = true)
            }
        }
    }
}
