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
    val container: AppContainer by lazy {
        AppContainer(this, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
