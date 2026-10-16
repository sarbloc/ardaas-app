package io.sarbloc.ardaas.translation

import java.io.File
import java.net.URI
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

// Test doubles for the on-device translation plumbing — port of
// ArdaasTests/TranslationTestDoubles.swift (the installer/optimizer half).
//
// The real artifact set is 359 MB of ONNX and cannot be in CI, so the download
// state machine is driven by a *synthetic* catalog: the same shape, but a few
// bytes each, with SHA-256s computed from the payloads. That keeps the hash
// gate genuinely exercised rather than stubbed out.

object TranslationFixtures {
    class SyntheticModel(val catalog: ModelCatalog, val payloads: Map<String, ByteArray>) {
        fun payload(name: String): ByteArray = payloads[name] ?: ByteArray(0)
    }

    /** The production shape (three graphs, two vocabulary files) with tiny, real, hash-matched payloads. */
    fun syntheticModel(cacheEstimate: Long = 4096): SyntheticModel {
        val specs = listOf(
            Triple("encoder_model.onnx", ModelCatalog.Kind.GRAPH, "encoder-graph-bytes"),
            Triple("decoder_model.onnx", ModelCatalog.Kind.GRAPH, "decoder-graph-bytes"),
            Triple("decoder_with_past_model.onnx", ModelCatalog.Kind.GRAPH, "decoder-with-past-graph-bytes"),
            Triple("tokenizer_src.json", ModelCatalog.Kind.VOCABULARY, "{\"tokenizer\":true}"),
            Triple("dict.TGT.json", ModelCatalog.Kind.VOCABULARY, "{\"dict\":true}"),
        )
        val payloads = specs.associate { (name, _, body) -> name to body.toByteArray() }
        val artifacts = specs.map { (name, kind, _) ->
            val data = payloads.getValue(name)
            ModelCatalog.Artifact(name, data.size.toLong(), ModelCatalog.sha256Hex(data), kind)
        }
        val catalog = ModelCatalog(
            artifacts = artifacts,
            repoBase = URI("https://example.invalid/model/"),
            directoryName = "SyntheticBentiModel",
            estimatedOptimizedCacheBytes = cacheEstimate,
        )
        return SyntheticModel(catalog, payloads)
    }
}

/** Collects warnings instead of hitting `android.util.Log`, which is a stub on the JVM. */
class RecordingLogger : TranslationLogger {
    val warnings: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override fun warn(message: String, error: Throwable?) {
        warnings += if (error == null) message else "$message: $error"
    }
}

/**
 * Serves canned bytes instead of hitting the network, and records what it was
 * asked for so the cellular guard and resume behaviour can be asserted.
 *
 * Resume works the way the real fetcher's does: whatever prefix is already in
 * the partial file is kept and only the rest of the payload is appended.
 */
class FakeModelFileFetcher(payloads: Map<String, ByteArray>) : ModelFileFetching {
    data class Request(val name: String, val allowsCellular: Boolean, val hadPartial: Boolean)

    private val lock = Any()
    private val payloads = payloads.toMutableMap()
    private val recorded = mutableListOf<Request>()

    /** Fail the named file once, then serve it normally on the next attempt. */
    val failOnce: MutableMap<String, Exception> = java.util.concurrent.ConcurrentHashMap()

    /** On a [failOnce] failure, leave the first half of the payload behind, as a dropped connection would. */
    @Volatile var leavesPartialOnFailure = false

    /** Simulates "only a metered network is up". */
    @Volatile var requiresCellular = false

    /** Suspend before serving, so a test can cancel mid-transfer. */
    @Volatile var delayMillis = 0L

    val requests: List<Request> get() = synchronized(lock) { recorded.toList() }
    val requestCount: Int get() = requests.size

    /** Replaces a payload with bytes that will not match the pinned hash. */
    fun corrupt(name: String) = setPayload(name, "corrupted-$name".toByteArray())

    fun setPayload(name: String, data: ByteArray) {
        synchronized(lock) { payloads[name] = data }
    }

    override suspend fun fetch(
        url: URL,
        partial: File,
        expectedBytes: Long,
        allowCellular: Boolean,
        onProgress: (Long) -> Unit,
    ) {
        val name = url.path.substringAfterLast('/')
        val existing = if (partial.isFile) partial.length() else 0L
        val payload = synchronized(lock) {
            recorded += Request(name, allowCellular, hadPartial = existing > 0)
            payloads[name]
        }
        currentCoroutineContext().ensureActive()
        if (delayMillis > 0) delay(delayMillis)

        if (requiresCellular && !allowCellular) throw TranslationError.CellularNotAllowed
        failOnce.remove(name)?.let { failure ->
            if (leavesPartialOnFailure && payload != null) partial.writeBytes(payload.copyOf(payload.size / 2))
            throw failure
        }
        if (payload == null) throw TranslationError.Download("no fixture for $name")

        if (existing in 1 until payload.size) {
            partial.appendBytes(payload.copyOfRange(existing.toInt(), payload.size))
        } else {
            partial.writeBytes(payload)
        }
        onProgress(payload.size / 2L)
        onProgress(payload.size.toLong())
    }
}

/**
 * Stands in for the ONNX engine behind [TranslationEngine]. Each "decode step"
 * checks cancellation exactly as [BentiTranslator] does and then runs
 * [onStep] — blocking, like an ORT run — so a test can hold a translation in
 * flight or cancel it mid-decode.
 */
class FakeTranslator(
    private val steps: Int = 1,
    private val onStep: (Int) -> Unit = {},
) : BentiTranslating {
    private val running = java.util.concurrent.atomic.AtomicInteger()
    private val mostRunning = java.util.concurrent.atomic.AtomicInteger()
    private val finished = java.util.concurrent.atomic.AtomicInteger()

    /** Highest number of translations ever inside this translator at once. */
    val maxConcurrent: Int get() = mostRunning.get()
    val completedCount: Int get() = finished.get()

    override suspend fun translate(english: String): BentiTranslation {
        mostRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        try {
            for (step in 0 until steps) {
                currentCoroutineContext().ensureActive()
                onStep(step)
            }
            finished.incrementAndGet()
            return BentiTranslation(
                gurmukhi = "ਅਰਦਾਸ $english",
                rawDevanagari = "अरदास",
                generatedIds = listOf(4L),
                latencyMillis = 0,
                sessionLoadMillis = 0,
                isGurmukhiPure = false,
            )
        } finally {
            running.decrementAndGet()
        }
    }
}

/**
 * Stands in for the ONNX Runtime graph rewrite: writes a stub optimized graph
 * plus the external-initializer side-car the real pass produces, so the cache
 * manifest, the validation and the source-graph retirement are all exercised
 * for real.
 */
class FakeGraphOptimizer : GraphOptimizing {
    private val lock = Any()
    private val optimized = mutableListOf<String>()
    private val verified = mutableListOf<String>()

    /**
     * Skip the side-car, reproducing an ORT build that cannot write external
     * initializers. The cache must reject this rather than silently forfeit
     * the whole memory saving.
     */
    @Volatile var writesSidecar = true
    @Volatile var optimizeError: Exception? = null

    /** Makes the self-sufficiency check fail, which must restore the originals. */
    @Volatile var verifyError: Exception? = null

    val optimizedGraphs: List<String> get() = synchronized(lock) { optimized.toList() }
    val verifiedGraphs: List<String> get() = synchronized(lock) { verified.toList() }

    override fun optimize(source: File, destination: File, externalDataName: String) {
        synchronized(lock) { optimized += source.name }
        optimizeError?.let { throw it }
        if (!source.isFile) throw TranslationError.MissingModelFile(source.name)
        destination.writeBytes(ByteArray(32) { 0x42 })
        if (writesSidecar) File(destination.parentFile, externalDataName).writeBytes(ByteArray(128) { 0x43 })
    }

    override fun verifyLoadable(graph: File) {
        synchronized(lock) { verified += graph.name }
        verifyError?.let { throw it }
        if (!graph.isFile) throw TranslationError.MissingModelFile(graph.name)
        val sidecar = File(graph.parentFile, ModelOptimizer.externalDataName(graph.name))
        if (!sidecar.isFile) throw TranslationError.MissingModelFile(sidecar.name)
    }
}
