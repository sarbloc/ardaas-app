package io.sarbloc.ardaas.translation

/**
 * One completed translation, plus the diagnostics a lab screen or a host
 * benchmark reads — port of `BentiTranslation` in
 * `Ardaas/Translation/BentiTranslation.swift`, minus the iOS memory-stage
 * sampler (a cable-run diagnostic with no Android counterpart yet).
 */
data class BentiTranslation(
    val gurmukhi: String,
    /** Model output after decode, cleanup and placeholder restore, before transliteration. */
    val rawDevanagari: String,
    /** The greedy decode's target ids, trailing `</s>` excluded. */
    val generatedIds: List<Long>,
    val latencyMillis: Long,
    /** Time spent creating ORT sessions for this translation (three, one per stage). */
    val sessionLoadMillis: Long,
    /** Script purity gate (spec section 5.5). Advisory — see [BentiPostprocessor.isGurmukhiPure]. */
    val isGurmukhiPure: Boolean,
)

/**
 * The inference engine, behind an interface so [TranslationEngine]'s
 * serialisation, memory guard and lifetime rules are testable without the
 * 359 MB model.
 *
 * Implementations block the calling thread for the whole translation; the
 * function suspends only so it can observe coroutine cancellation between
 * decode steps. [TranslationEngine] is what serialises calls.
 */
interface BentiTranslating {
    /**
     * @throws TranslationError on any failure.
     * @throws kotlin.coroutines.cancellation.CancellationException when the
     *   caller is cancelled — never a partial translation.
     */
    suspend fun translate(english: String): BentiTranslation
}
