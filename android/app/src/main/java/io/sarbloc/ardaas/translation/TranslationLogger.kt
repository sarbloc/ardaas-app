package io.sarbloc.ardaas.translation

import android.util.Log

/**
 * Where the model layer reports failures it deliberately carries on past
 * (best-effort cleanup, a stale manifest it is about to rebuild). Never silent,
 * but injected rather than calling [Log] directly so the installer and the
 * cache logic run as plain JVM tests, where `android.util.Log` is a stub.
 */
fun interface TranslationLogger {
    fun warn(message: String, error: Throwable?)

    /** Production sink: logcat under one tag, so the whole model layer greps together. */
    object Android : TranslationLogger {
        private const val TAG = "BentiModel"

        override fun warn(message: String, error: Throwable?) {
            Log.w(TAG, message, error)
        }
    }
}
