package io.sarbloc.ardaas.translation

/**
 * The lifecycle of the on-device model, as the UI sees it — port of
 * `Ardaas/Translation/ModelState.swift`. Deliberately the whole surface: the
 * Compose screen renders exactly these five cases and never reaches behind
 * them for file paths or jobs.
 */
sealed interface ModelState {
    /**
     * Nothing (or only a resumable partial download) is on disk. A fresh
     * install starts here and deleting the model returns here; nothing is
     * fetched until a download is asked for explicitly.
     */
    data object NotDownloaded : ModelState

    /** Fetching artifacts. [progress] is 0..1 over the download only, not the optimization after it. */
    data class Downloading(val progress: Double) : ModelState

    /** One-time on-device graph optimization (docs/translation/MEMORY.md). No progress fraction. */
    data object Optimizing : ModelState

    /** Verified, optimized and loadable. Translation only works here. */
    data object Ready : ModelState

    /** Terminal until the caller retries the download or deletes the model. */
    data class Failed(override val error: TranslationError) : ModelState

    val isReady: Boolean get() = this == Ready

    /** True while an install is in flight, so the UI can offer Cancel and suppress a second tap. */
    val isWorking: Boolean get() = this is Downloading || this == Optimizing

    /** The failure, in [Failed]; null in every other state. */
    val error: TranslationError? get() = null
}
