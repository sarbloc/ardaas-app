package io.sarbloc.ardaas.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.ui.components.BackTopAppBar

// Stand-ins so navigation is complete end to end before the real screens
// land. Each is replaced wholesale, at its one call site in AppRoot.

/** Stand-in for the Reader screen: the record's label as its title. */
@Composable
fun ReaderPlaceholderScreen(savedArdaas: SavedArdaas, onBack: () -> Unit) {
    PlaceholderScreen(title = savedArdaas.label, body = stringResource(R.string.reader_placeholder_body), onBack = onBack)
}

/** Stand-in for the Compose screen (#89). */
@Composable
fun ComposePlaceholderScreen(onBack: () -> Unit) {
    PlaceholderScreen(
        title = stringResource(R.string.compose_ardaas),
        body = stringResource(R.string.compose_placeholder_body),
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(title: String, body: String, onBack: () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { BackTopAppBar(title, onBack) },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = ArdaasColors.Mist,
                textAlign = TextAlign.Center,
            )
        }
    }
}
