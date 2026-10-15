package io.sarbloc.ardaas.app

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class LoadStateTest {

    @Test
    fun loadCatchingWrapsTheValue() {
        assertEquals(LoadState.Loaded(42), loadCatching { 42 })
    }

    @Test
    fun loadCatchingCapturesAFailure() {
        val error = IllegalStateException("broken bundle")
        assertEquals(LoadState.Failed(error), loadCatching { throw error })
    }

    @Test
    fun loadCatchingRethrowsCancellation() {
        assertThrows(CancellationException::class.java) {
            loadCatching { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun valueOrNullIsTheValueOnlyOnceLoaded() {
        assertEquals("x", LoadState.Loaded("x").valueOrNull)
        assertNull(LoadState.Loading.valueOrNull)
        assertNull(LoadState.Failed(RuntimeException()).valueOrNull)
    }

    @Test
    fun mapTransformsOnlyALoadedValue() {
        val failed = LoadState.Failed(RuntimeException())
        assertEquals(LoadState.Loaded(2), LoadState.Loaded(1).map { it + 1 })
        assertSame(LoadState.Loading, LoadState.Loading.map { _: Int -> 0 })
        assertSame(failed, (failed as LoadState<Int>).map { it + 1 })
    }

    @Test
    fun asLoadStatesStartsLoadingThenEmitsEachValue() = runBlocking {
        assertEquals(
            listOf(LoadState.Loading, LoadState.Loaded(1), LoadState.Loaded(2)),
            flowOf(1, 2).asLoadStates().toList(),
        )
    }

    @Test
    fun asLoadStatesTurnsAnUpstreamErrorIntoFailedAndReportsIt() = runBlocking {
        val error = IllegalStateException("database will not open")
        val reported = mutableListOf<Throwable>()
        val states = flow<Int> {
            emit(1)
            throw error
        }.asLoadStates { reported += it }.toList()

        assertEquals(listOf(LoadState.Loading, LoadState.Loaded(1), LoadState.Failed(error)), states)
        assertEquals(listOf<Throwable>(error), reported)
    }
}
