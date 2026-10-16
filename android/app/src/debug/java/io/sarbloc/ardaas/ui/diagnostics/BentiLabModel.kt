package io.sarbloc.ardaas.ui.diagnostics

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.sarbloc.ardaas.translation.BentiTranslationService
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Benti Lab's state and actions, kept out of the composables so the
 * screen is layout only. Drives [BentiTranslationService] through exactly
 * the public API the production UI (#120) will use — the lab is a consumer,
 * not a back door.
 *
 * @param scope the screen's scope: leaving the lab cancels a translation or
 *   benchmark in flight (the service's own installs and deletes carry on).
 */
@Stable
class BentiLabModel(
    private val service: BentiTranslationService,
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    var input by mutableStateOf(PRESETS.first())
    var allowCellular by mutableStateOf(false)

    /** A translation or benchmark is running; the lab runs one at a time so timings never overlap. */
    var isBusy by mutableStateOf(false)
        private set

    /** Every translation this visit, oldest first. */
    var runs by mutableStateOf<List<LabRun>>(emptyList())
        private set

    var benchmark by mutableStateOf<BenchmarkSummary?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var memoryNow by mutableStateOf(ProcessMemory.snapshot())
        private set

    var device by mutableStateOf(DeviceProfile.read(appContext))
        private set

    val latestRun: LabRun? get() = runs.lastOrNull()

    /** Re-reads the model's disk state and this process's memory, e.g. on appearance. */
    fun refresh() {
        service.refresh()
        refreshReadings()
    }

    fun refreshReadings() {
        memoryNow = ProcessMemory.snapshot()
        device = DeviceProfile.read(appContext)
    }

    fun download() = service.download(allowingCellular = allowCellular)

    fun cancelDownload() = service.cancelDownload()

    fun releaseEngine() {
        service.releaseEngine()
        refreshReadings()
    }

    fun deleteModel() {
        scope.launch {
            service.deleteModel()
            refreshReadings()
        }
    }

    fun translate() = runExclusive {
        runs = runs + measure(input)
    }

    /**
     * Translates every preset in turn and summarises them. Sequential on
     * purpose: the engine serialises anyway, and overlapping calls would
     * charge one translation's wait to another's latency.
     */
    fun runBenchmark() = runExclusive {
        benchmark = null
        val batch = mutableListOf<LabRun>()
        for (preset in PRESETS) {
            val run = measure(preset)
            batch += run
            runs = runs + run
        }
        benchmark = BenchmarkSummary.of(batch)
    }

    fun report(): String = LabReport.format(
        timestamp = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        device = device,
        modelState = service.state.value,
        installedBytes = service.installedBytes.value,
        memoryNow = memoryNow,
        runs = runs,
        benchmark = benchmark,
    )

    private fun runExclusive(work: suspend () -> Unit) {
        if (isBusy) return
        isBusy = true
        error = null
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Benti Lab translation failed", e)
                error = e.message ?: e.toString()
            } finally {
                isBusy = false
                refreshReadings()
            }
        }
    }

    private suspend fun measure(english: String): LabRun {
        val start = System.nanoTime()
        val (translation, sampledPeak) = ProcessMemory.sampledPeakRss { service.translate(english) }
        val wallMillis = (System.nanoTime() - start) / NANOS_PER_MILLI
        return LabRun(
            english = english,
            gurmukhi = translation.gurmukhi,
            isGurmukhiPure = translation.isGurmukhiPure,
            wallMillis = wallMillis,
            engineMillis = translation.latencyMillis,
            sessionLoadMillis = translation.sessionLoadMillis,
            sampledPeakRssBytes = sampledPeak,
            after = ProcessMemory.snapshot(),
        )
    }

    companion object {
        private const val TAG = "BentiLab"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Short benti-style sentences: the shape of what users will type, small enough to run as a batch. */
        val PRESETS = listOf(
            "Please bless my family with health and happiness.",
            "Grant us the strength to serve others with humility.",
            "We pray for peace for our grandmother who has passed away.",
            "Bless our newborn daughter with a long and righteous life.",
            "Forgive our mistakes and keep us in your will.",
        )
    }
}
