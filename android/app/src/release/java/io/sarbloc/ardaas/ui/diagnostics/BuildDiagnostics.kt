package io.sarbloc.ardaas.ui.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.sarbloc.ardaas.translation.BentiTranslationService

/** Release builds ship no diagnostics: no Home entry, and no screen to reach. */
object BuildDiagnostics : DiagnosticsHook {
    @Composable
    override fun HomeAction(onOpen: () -> Unit) = Unit

    @Composable
    override fun Screen(translationService: () -> BentiTranslationService, onBack: () -> Unit) {
        LaunchedEffect(Unit) { onBack() }
    }
}
