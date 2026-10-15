package io.sarbloc.ardaas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The brand gradient behind a whole screen — the Android counterpart of iOS
 * `.themedScreen()`. It fills the window edge to edge, behind the system bars;
 * content inside is responsible for its own insets (Scaffold handles them).
 * Put screen containers on top with a transparent background so it shows.
 */
@Composable
fun ArdaasBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().background(ArdaasColors.BackgroundGradient),
        content = content,
    )
}
