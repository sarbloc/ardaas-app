package io.sarbloc.ardaas.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark by design, matching iOS. Deliberately the stock dark scheme for now:
 * the real palette and typography land with the theme work (#87).
 */
@Composable
fun ArdaasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
