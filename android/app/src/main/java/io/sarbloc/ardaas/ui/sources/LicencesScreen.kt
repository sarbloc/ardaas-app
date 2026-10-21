package io.sarbloc.ardaas.ui.sources

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.app.LoadState
import io.sarbloc.ardaas.app.loadCatching
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.components.BackTopAppBar
import io.sarbloc.ardaas.ui.components.GroupedSection
import io.sarbloc.ardaas.ui.components.SectionDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "Ardaas"

/** One licence and its text as loaded from the assets. */
private typealias LoadedLicence = Pair<OpenSourceLicence, LoadState<String>>

/**
 * Open-source licences (#132): the full notice of every third-party component
 * in [OpenSourceLicence.all], read from the bundled assets. Pushed from About
 * & Sources. A text that fails to load says so in its card rather than
 * leaving it blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val licences by produceState(initialValue = emptyList<LoadedLicence>(), assets) {
        value = withContext(Dispatchers.IO) {
            OpenSourceLicence.all.map { licence ->
                val text = loadCatching { OpenSourceLicence.reflow(licence.loadText(assets::open)) }
                if (text is LoadState.Failed) {
                    Log.e(TAG, "Licence text ${licence.assetName} failed to load", text.error)
                }
                licence to text
            }
        }
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = { BackTopAppBar(stringResource(R.string.licences_title), onBack, scrollBehavior) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(licences, key = { (licence, _) -> licence.assetName }) { (licence, text) ->
                GroupedSection(licence.component) {
                    LicenceRow(licence.source, ArdaasColors.Parchment)
                    SectionDivider()
                    when (text) {
                        is LoadState.Loaded -> SelectionContainer { LicenceRow(text.value, ArdaasColors.Mist) }
                        is LoadState.Failed -> LicenceRow(
                            stringResource(R.string.licences_load_failed, text.error.message ?: text.error.toString()),
                            ArdaasColors.Mist,
                        )
                        LoadState.Loading -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenceRow(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
