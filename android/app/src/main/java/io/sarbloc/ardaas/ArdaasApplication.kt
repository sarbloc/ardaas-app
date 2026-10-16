package io.sarbloc.ardaas

import android.app.Application
import io.sarbloc.ardaas.app.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the process-wide [AppContainer] — the counterpart of iOS's
 * `.modelContainer(for:)` on the app scene. Created lazily, so a process that
 * never shows UI (a Robolectric test, say) opens nothing.
 */
class ArdaasApplication : Application() {
    private val containerHolder = lazy {
        AppContainer(this, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    val container: AppContainer by containerHolder

    /**
     * Android's counterpart of iOS's memory warning, which the iOS service
     * observes itself. Here the app is what receives it, so it forwards —
     * without building the container just to say there is nothing to free.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (containerHolder.isInitialized()) container.onTrimMemory(level)
    }
}
