package io.sarbloc.ardaas.ui.compose

import androidx.annotation.StringRes
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.translation.TranslationError

/**
 * User-facing wording for the failures reachable from Compose — iOS
 * `ComposeView.message(for:)`. Null means "no friendlier wording": the
 * caller shows the error's own text rather than hiding it, because a silent
 * failure would be worse than a technical one.
 */
@StringRes
fun translationErrorMessage(error: TranslationError): Int? = when (error) {
    TranslationError.CellularNotAllowed -> R.string.translation_error_cellular
    is TranslationError.InsufficientDisk -> R.string.translation_error_disk
    is TranslationError.InsufficientMemory -> R.string.translation_error_memory
    is TranslationError.InputTooLong -> R.string.translation_error_too_long
    TranslationError.Cancelled -> R.string.translation_error_cancelled
    TranslationError.EngineUnavailable, TranslationError.NotReady -> R.string.translation_error_not_ready
    else -> null
}
