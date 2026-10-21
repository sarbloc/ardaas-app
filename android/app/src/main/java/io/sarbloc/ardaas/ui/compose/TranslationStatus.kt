package io.sarbloc.ardaas.ui.compose

import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.translation.BentiPostprocessor
import io.sarbloc.ardaas.translation.ModelState
import io.sarbloc.ardaas.translation.TranslationError

// The translate half of iOS's ComposeView (#44) as pure data: every rule that
// decides what the translate section shows, whether Save waits, and when the
// draft carries a caution. Kept out of the composables so each (model state,
// draft, request) combination is a plain JVM test.

/** Why the last translation the user asked for produced no draft. Shown inline under the section. */
sealed interface TranslateFailure {
    /** The engine ran and returned only whitespace. */
    data object EmptyResult : TranslateFailure

    /** The engine (or the service in front of it) failed. */
    data class Failed(val error: TranslationError) : TranslateFailure
}

/**
 * A finished translation that has not been written into the draft yet. The
 * draft lives in the screen's saveable state and the translation in a
 * session that outlives the screen's composition, so the result is parked
 * here and applied by the screen — including by a screen recreated after a
 * configuration change that happened while the translation ran.
 */
data class CompletedTranslation(
    val gurmukhi: String,
    /** The trimmed English it was translated from: [BentiDraft.translatedFrom] once applied. */
    val source: String,
)

/**
 * What the translate section shows, by [ModelState] — iOS's
 * `translateSection` switch, as data.
 */
sealed interface TranslateSection {
    /**
     * Not downloaded, or ready: the Translate button plus a one-line note —
     * the size disclosure when [needsDownload], the on-device promise
     * otherwise.
     */
    data class Offer(val button: TranslateButton, val needsDownload: Boolean) : TranslateSection

    /**
     * The model is downloading. [isAwaitingTranslation] picks the copy: when
     * the user is waiting on a translation, leaving cancels the download and
     * Save waits; otherwise the download simply resumes next time.
     */
    data class Downloading(val progress: Float, val percent: Int, val isAwaitingTranslation: Boolean) : TranslateSection

    /**
     * The one-time optimization, which cannot be interrupted. [offersStopWaiting]
     * when a translation is queued behind it — Save is waiting on that, and
     * Cancel download no longer exists, so this is the only way out of the wait.
     */
    data class Preparing(val offersStopWaiting: Boolean) : TranslateSection

    /** The install failed; "Try again" reopens consent. */
    data class Failed(val error: TranslationError) : TranslateSection
}

/**
 * The Translate button. [opensConsent] when tapping it must show the
 * download consent first (nothing is downloaded without it); otherwise it
 * translates.
 */
data class TranslateButton(val label: Label, val isEnabled: Boolean, val opensConsent: Boolean) {
    enum class Label { TRANSLATE, TRANSLATE_AGAIN, TRANSLATING }
}

/**
 * Everything the translation flow knows at one instant: the model's state
 * and the session's request bookkeeping. Pure, so the screen's rules —
 * iOS's `isAwaitingTranslation`, `canSave`, `showsTranslationSection` and
 * the section switch — are tested without a device.
 *
 * There is no engine-availability term (iOS's `TranslationBuild
 * .isEngineAvailable`): every Android build links ONNX Runtime, and an APK
 * without native code for the device's ABI does not install at all, so there
 * is nothing to gate. A runtime that still fails to start surfaces as a
 * translation error, as iOS's does once linked.
 */
data class TranslationStatus(
    val model: ModelState,
    /** A translation is running. */
    val isTranslating: Boolean = false,
    /**
     * The user asked to translate but the model had to be installed first;
     * the session translates for them once it is ready.
     */
    val translateWhenReady: Boolean = false,
    /** A finished translation the screen has not applied yet. */
    val pendingResult: CompletedTranslation? = null,
    val failure: TranslateFailure? = null,
) {
    /**
     * A translation the user explicitly asked for is still coming: running,
     * finished but not yet in the draft, or queued behind the install they
     * just consented to.
     *
     * Saving now would persist the layers as they were *before* the result
     * lands — an English-only record on a first translate, or the previous
     * Gurmukhi against edited English on a re-translate — and leaving Compose
     * cancels the install as well. So Save waits it out.
     *
     * iOS counts a queued request only while the install [ModelState.isWorking];
     * here it also counts on [ModelState.Ready], covering the instant between
     * the install finishing and the session starting the queued translation
     * (iOS does both in one main-actor turn; here they are a collector hop
     * apart).
     */
    val isAwaitingTranslation: Boolean
        get() = isTranslating || pendingResult != null || (translateWhenReady && (model.isWorking || model.isReady))

    /** iOS's `canSave`: something worth saving, and no translation on its way. */
    fun canSave(draft: ComposeDraft): Boolean = draft.canSave && !isAwaitingTranslation

    /**
     * Whether the translate section is on screen at all: hidden for Gurmukhi
     * input (nothing to translate) — but shown, disabled, for an empty
     * editor, so the feature is discoverable before anything is typed.
     *
     * The [ModelState.isWorking] term keeps an install visible when the user
     * switches to Gurmukhi mid-download: the transfer carries on regardless,
     * and hiding it would hide the progress *and* the only Cancel button. A
     * failure is not held open the same way — the state is sticky, so it
     * surfaces again as soon as the text is translatable again.
     */
    fun showsSection(benti: BentiDraft): Boolean = !benti.isTypedInGurmukhi || model.isWorking

    /**
     * The editable Gurmukhi draft shows under the section once a translation
     * has landed, until Remove Gurmukhi — emptying the editor does not hide it
     * (#131). False whenever the typed text is Gurmukhi, so a draft from
     * earlier English stays hidden while it is ignored.
     */
    fun showsDraft(benti: BentiDraft): Boolean = showsSection(benti) && benti.showsTranslationDraft

    /** iOS's `translateSection` switch. */
    fun section(benti: BentiDraft): TranslateSection = when (val state = model) {
        ModelState.NotDownloaded -> TranslateSection.Offer(button(benti, opensConsent = true), needsDownload = true)
        ModelState.Ready -> TranslateSection.Offer(button(benti, opensConsent = false), needsDownload = false)
        is ModelState.Downloading -> {
            val progress = state.progress.coerceIn(0.0, 1.0)
            TranslateSection.Downloading(
                progress = progress.toFloat(),
                // Truncated, as iOS's Int(progress * 100): 100% only when it is.
                percent = (progress * PERCENT).toInt(),
                isAwaitingTranslation = translateWhenReady,
            )
        }
        ModelState.Optimizing -> TranslateSection.Preparing(offersStopWaiting = translateWhenReady)
        is ModelState.Failed -> TranslateSection.Failed(state.error)
    }

    private fun button(benti: BentiDraft, opensConsent: Boolean) = TranslateButton(
        label = when {
            isTranslating -> TranslateButton.Label.TRANSLATING
            benti.showsTranslationDraft -> TranslateButton.Label.TRANSLATE_AGAIN
            else -> TranslateButton.Label.TRANSLATE
        },
        isEnabled = benti.isTranslatable && !isTranslating,
        opensConsent = opensConsent,
    )

    companion object {
        private const val PERCENT = 100

        /**
         * The model's output is meant to be Gurmukhi only; a word left in
         * Devanagari or Latin is the classic failure of a small model. Derived
         * from the draft as it stands — the same check as the engine's
         * advisory [io.sarbloc.ardaas.translation.BentiTranslation.isGurmukhiPure]
         * flag, so it agrees with it the moment a translation lands — rather
         * than stored: it then survives process death with the draft, and
         * clears as soon as the user corrects the stray words.
         */
        fun showsPurityCaution(benti: BentiDraft): Boolean =
            benti.hasTranslationDraft && !BentiPostprocessor.isGurmukhiPure(benti.gurmukhi)
    }
}
