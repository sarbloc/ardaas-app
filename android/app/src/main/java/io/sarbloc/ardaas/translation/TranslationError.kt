package io.sarbloc.ardaas.translation

import kotlin.coroutines.cancellation.CancellationException

/**
 * Every failure the on-device translation stack can surface — port of
 * `Ardaas/Translation/TranslationError.swift`. Value-equal, so [ModelState]
 * is, which keeps the install state machine assertable in tests.
 *
 * The model files are downloaded at runtime from a third party, so every case
 * here is a real, reachable outcome: nothing in this stack crashes instead.
 */
sealed class TranslationError(message: String) : Exception(message) {

    /** A downloaded file did not match its pinned SHA-256. Never retried silently. */
    data class HashMismatch(val file: String, val expected: String, val actual: String) :
        TranslationError("SHA-256 mismatch for $file: expected $expected, got $actual")

    /** Transport-level failure (HTTP status, connectivity, timeout). */
    data class Download(val detail: String) : TranslationError("Download failed: $detail")

    /** The download needs the network, but only a metered one is available and cellular was not allowed. */
    data object CellularNotAllowed : TranslationError("Wi-Fi is required for this download.")

    /** The user cancelled. */
    data object Cancelled : TranslationError("Cancelled.")

    /** Less memory headroom than the pipeline's measured peak needs; refused rather than risk a kill. */
    data class InsufficientMemory(val availableBytes: Long, val requiredBytes: Long) : TranslationError(
        "Not enough free memory: ${availableBytes / MIB} MB available, ${requiredBytes / MIB} MB needed.",
    )

    /** Not enough free space for the download plus the optimized cache, which coexist until the originals go. */
    data class InsufficientDisk(val availableBytes: Long, val requiredBytes: Long) : TranslationError(
        "Not enough free space: ${availableBytes / MB} MB available, ${requiredBytes / MB} MB needed.",
    )

    /** The one-time graph optimization failed, or produced a cache that could not be loaded back. */
    data class OptimizationFailed(val graph: String, val detail: String) :
        TranslationError("Failed to prepare $graph: $detail")

    /** A file the pipeline needs is not on disk. */
    data class MissingModelFile(val name: String) : TranslationError("Missing model file: $name")

    /** A file is on disk but is not what it claims to be. */
    data class MalformedModelFile(val name: String, val detail: String) :
        TranslationError("Malformed $name: $detail")

    /** This build carries no ONNX Runtime, so no inference is possible. */
    data object EngineUnavailable : TranslationError("This build was made without the on-device translation engine.")

    /** `translate` was called before the model reached [ModelState.Ready]. */
    data object NotReady : TranslationError("The translation model is not ready.")

    /** Source text exceeds the model's positional limit. */
    data class InputTooLong(val tokens: Int, val limit: Int) :
        TranslationError("Text is too long: $tokens tokens, limit $limit.")

    /** The ONNX session could not be created or run. */
    data class SessionFailure(val detail: String) : TranslationError("ONNX session failure: $detail")

    /** The decode loop produced something unusable. */
    data class DecodeFailure(val detail: String) : TranslationError("Decode failure: $detail")

    /** Filesystem failure, carried as text. */
    data class FileSystem(val detail: String) : TranslationError("Filesystem error: $detail")

    companion object {
        private const val MIB = 1_048_576L
        private const val MB = 1_000_000L

        /**
         * Maps an arbitrary throwable onto this hierarchy without losing its
         * text — for surfacing as [ModelState.Failed]. Coroutine code must
         * still rethrow a [CancellationException] rather than swallow it;
         * this only names it for display.
         */
        fun wrap(error: Throwable): TranslationError = when (error) {
            is TranslationError -> error
            is CancellationException -> Cancelled
            else -> FileSystem(error.message ?: error.toString())
        }
    }
}
