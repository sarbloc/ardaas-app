package io.sarbloc.ardaas.ui.diagnostics

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.translation.BentiTranslationService

/**
 * Debug builds: the Benti Lab, reached from a flask in Home's top bar — as
 * iOS gates it in `HomeView` ("On-device translation diagnostics (#42) —
 * debug builds only"). Release's `BuildDiagnostics` is a no-op.
 */
object BuildDiagnostics : DiagnosticsHook {
    @Composable
    override fun HomeAction(onOpen: () -> Unit) {
        IconButton(onClick = onOpen) {
            Icon(painterResource(R.drawable.ic_science), contentDescription = "Benti Lab")
        }
    }

    @Composable
    override fun Screen(translationService: () -> BentiTranslationService, onBack: () -> Unit) {
        val service = remember { translationService() }
        BentiLabScreen(service, onBack)
    }
}
