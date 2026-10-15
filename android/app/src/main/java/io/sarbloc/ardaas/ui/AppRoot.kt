package io.sarbloc.ardaas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Placeholder root. Replaced by the Home screen (#88). */
@Composable
fun AppRoot() {
    ArdaasBackground {
        // Transparent so the gradient shows through, edge to edge.
        Scaffold(containerColor = Color.Transparent) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Ardaas",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // A glimpse of the three reading layers, so the palette and
                // reader type are visibly in use until real screens land.
                Text("ਅਰਦਾਸ", style = ReaderTextStyles.Gurmukhi, textAlign = TextAlign.Center)
                Text("Ardaas", style = ReaderTextStyles.Transliteration, textAlign = TextAlign.Center)
                Text("Supplication", style = ReaderTextStyles.Translation, textAlign = TextAlign.Center)
            }
        }
    }
}

@Preview
@Composable
private fun AppRootPreview() {
    ArdaasTheme { AppRoot() }
}
