package io.sarbloc.ardaas.ui.diagnostics

import androidx.compose.runtime.Composable
import io.sarbloc.ardaas.translation.BentiTranslationService

/**
 * Seam for debug-only diagnostics (#119) — the Android form of iOS's
 * `#if DEBUG` around the Benti Lab in `HomeView`.
 *
 * Each build type supplies its own `BuildDiagnostics` object from its own
 * source set: `src/debug` carries the lab, `src/release` a no-op. Main code
 * only ever sees this interface, so the lab's classes, strings and drawables
 * are not compiled into a release build at all — no reliance on R8, which
 * release does not run.
 */
interface DiagnosticsHook {
    /** Home's top-bar entry to the diagnostics screen. Draws nothing in release. */
    @Composable
    fun HomeAction(onOpen: () -> Unit)

    /**
     * The diagnostics screen. [translationService] is a provider so the
     * service is only built when the screen actually shows. Release never
     * navigates here; if a restored back stack somehow does, it leaves.
     */
    @Composable
    fun Screen(translationService: () -> BentiTranslationService, onBack: () -> Unit)
}
