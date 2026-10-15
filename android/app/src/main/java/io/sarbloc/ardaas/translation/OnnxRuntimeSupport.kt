package io.sarbloc.ardaas.translation

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * The process-wide ONNX Runtime environment.
 *
 * ORT allows one per process and the Java binding never frees it
 * (`OrtEnvironment.close()` is a no-op), so callers fetch it and never own it.
 *
 * @throws TranslationError.SessionFailure when the runtime cannot start — the
 *   native library failed to load (an ABI this APK carries no `.so` for) or the
 *   environment could not be created. That is a missing engine, not a crash,
 *   which is why the [LinkageError] is caught here rather than left to unwind.
 */
internal fun ortEnvironment(): OrtEnvironment = try {
    OrtEnvironment.getEnvironment()
} catch (e: LinkageError) {
    throw TranslationError.SessionFailure("ONNX Runtime native library did not load: $e")
} catch (e: IllegalStateException) {
    throw TranslationError.SessionFailure("ONNX Runtime environment could not be created: ${e.message ?: e}")
}

/**
 * Creates a session over [graph] with options built by [configure].
 *
 * Always passes explicit options: `createSession(path)` allocates a native
 * `SessionOptions` it never closes. The options are closed as soon as the
 * session exists — ORT copies them into the session, so nothing outlives this
 * call but the session itself, which the caller owns and must close.
 */
internal fun OrtEnvironment.openSession(
    graph: File,
    configure: OrtSession.SessionOptions.() -> Unit = {},
): OrtSession = OrtSession.SessionOptions().use { options ->
    options.configure()
    createSession(graph.path, options)
}
