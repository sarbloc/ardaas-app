package io.sarbloc.ardaas.translation

import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import java.io.File

/**
 * The ONNX Runtime implementation of the one-time graph rewrite — port of
 * `Ardaas/Translation/OnnxGraphOptimizer.swift`.
 *
 * [optimize] creates a throwaway session purely for its side effect: ORT
 * writes the optimized graph plus its external-initializer blob to disk. The
 * session is never run and is closed immediately.
 *
 * The pass must run CPU-only. Registering NNAPI (or any execution provider
 * that produces compiled nodes) makes ORT refuse to serialize with "Unable to
 * serialize model as it contains compiled nodes". No provider is added
 * anywhere in this stack, so CPU-only is simply the default — but it is a trap
 * for anyone who later adds one.
 */
object OnnxGraphOptimizer : GraphOptimizing {
    /** Side-car file name, relative to the optimized graph's own directory. */
    private const val EXTERNAL_INITIALIZERS_FILE = "session.optimized_model_external_initializers_file_name"
    private const val EXTERNAL_INITIALIZERS_MIN_BYTES = "session.optimized_model_external_initializers_min_size_in_bytes"
    private const val SAVE_PREPACKED_EXTERNALLY = "session.save_external_prepacked_constant_initializers"

    /** Anything smaller than a page is not worth a mapping. */
    private const val MIN_EXTERNAL_BYTES = "4096"

    override fun optimize(source: File, destination: File, externalDataName: String) {
        if (!source.isFile) throw TranslationError.MissingModelFile(source.name)
        failingAsOptimization(source.name) {
            ortEnvironment().openSession(source) {
                setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
                setOptimizationLevel(OptLevel.ALL_OPT)
                setOptimizedModelFilePath(destination.path)
                // Written relative to the optimized model's directory, which is
                // why the cache never reaches back to the originals.
                addConfigEntry(EXTERNAL_INITIALIZERS_FILE, externalDataName)
                addConfigEntry(EXTERNAL_INITIALIZERS_MIN_BYTES, MIN_EXTERNAL_BYTES)
                // Pre-packed weights go to the same file, so they are mapped
                // rather than heap-allocated on every later session creation.
                // Worth 65 MB of peak; the alternative (disable_prepacking)
                // reaches the same peak but costs ~26% decode latency.
                addConfigEntry(SAVE_PREPACKED_EXTERNALLY, "1")
            }.close()
        }
    }

    override fun verifyLoadable(graph: File) {
        failingAsOptimization(graph.name) {
            ortEnvironment().openSession(graph).use { session ->
                // Touching the IO metadata forces ORT past lazy initialization,
                // so a graph that cannot resolve its external initializers
                // fails here rather than at the first translation.
                session.inputInfo
                session.outputInfo
            }
        }
    }

    /** Every runtime failure in this pass is reported as the graph it happened to. */
    private inline fun failingAsOptimization(graph: String, block: () -> Unit) {
        try {
            block()
        } catch (e: OrtException) {
            throw TranslationError.OptimizationFailed(graph, e.message ?: e.toString())
        } catch (e: TranslationError.SessionFailure) {
            throw TranslationError.OptimizationFailed(graph, e.detail)
        }
    }
}
