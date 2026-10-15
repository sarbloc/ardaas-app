package io.sarbloc.ardaas.app

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Something the app loads once and screens render in all three states — the
 * Android counterpart of iOS's `Result<…, Error>?` (`nil` = still loading).
 * A failure is carried to the screen, never swallowed: a broken bundle or
 * database must show up as an error, not as a crash or an empty screen.
 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    data class Loaded<out T>(val value: T) : LoadState<T>

    data class Failed(val error: Throwable) : LoadState<Nothing>

    /** The value once loaded; null while loading or after a failure. */
    val valueOrNull: T?
        get() = (this as? Loaded<T>)?.value
}

/** The loaded value transformed; loading and failure pass through unchanged. */
inline fun <T, R> LoadState<T>.map(transform: (T) -> R): LoadState<R> = when (this) {
    LoadState.Loading -> LoadState.Loading
    is LoadState.Failed -> this
    is LoadState.Loaded -> LoadState.Loaded(transform(value))
}

/**
 * Runs [block], capturing an ordinary failure as [LoadState.Failed].
 * Cancellation is rethrown, so a cancelled load is never mistaken for a
 * broken bundle.
 */
inline fun <T> loadCatching(block: () -> T): LoadState<T> = try {
    LoadState.Loaded(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    LoadState.Failed(e)
}

/**
 * This flow as load states: [LoadState.Loading] until the first value, then
 * each value as [LoadState.Loaded]. An upstream error ends the flow with
 * [LoadState.Failed] instead of propagating to the collector — which, for a
 * screen, would be a crash. [onError] is told first, so the failure is
 * logged with context rather than only drawn.
 */
fun <T> Flow<T>.asLoadStates(onError: (Throwable) -> Unit = {}): Flow<LoadState<T>> =
    map<T, LoadState<T>> { LoadState.Loaded(it) }
        .onStart { emit(LoadState.Loading) }
        .catch { error ->
            onError(error)
            emit(LoadState.Failed(error))
        }
