package io.sarbloc.ardaas.ui.sources

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.app.LoadState
import io.sarbloc.ardaas.content.ArdaasLibrary
import io.sarbloc.ardaas.ui.ArdaasBackground
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.ArdaasTheme
import io.sarbloc.ardaas.ui.components.BackTopAppBar
import io.sarbloc.ardaas.ui.components.GroupedSection
import io.sarbloc.ardaas.ui.components.SectionDivider

/** The published support page. */
const val SUPPORT_URL = "https://sarbloc.github.io/ardaas-app/"

private const val TAG = "Ardaas"

/**
 * About & Sources: the sources behind each bundled text — read from the
 * bundle itself, never hardcoded — the accuracy policy, a link to the support
 * page, and the app version. Port of `SourcesView.swift`; a bundle that
 * failed to load shows the failure rather than an empty page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    library: LoadState<ArdaasLibrary>,
    version: String,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val openUrl: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (e: Exception) {
            // No browser to handle it: nothing useful to show, but say why.
            Log.w(TAG, "Could not open $url", e)
        }
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = { BackTopAppBar(stringResource(R.string.about_and_sources), onBack, scrollBehavior) },
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
            when (library) {
                is LoadState.Loaded -> items(library.value.variants, key = { it.id }) { variant ->
                    GroupedSection(stringResource(R.string.sources_variant_header, variant.displayName)) {
                        variant.content.sources.forEachIndexed { index, source ->
                            if (index > 0) SectionDivider()
                            SourceRow(SourceEntry.of(source), openUrl)
                        }
                    }
                }
                is LoadState.Failed -> item {
                    GroupedSection(stringResource(R.string.sources_texts_header)) {
                        ProseRow(
                            stringResource(
                                R.string.sources_load_failed,
                                library.error.message ?: library.error.toString(),
                            ),
                        )
                    }
                }
                LoadState.Loading -> item {
                    GroupedSection(stringResource(R.string.sources_texts_header)) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            item {
                GroupedSection(stringResource(R.string.sources_accuracy_header)) {
                    ProseRow(stringResource(R.string.sources_accuracy_body))
                }
            }
            item {
                GroupedSection(stringResource(R.string.sources_support_header)) {
                    LinkRow(stringResource(R.string.sources_support_link)) { openUrl(SUPPORT_URL) }
                }
            }
            item {
                GroupedSection(header = null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.sources_version), color = ArdaasColors.Parchment)
                        Text(version, color = ArdaasColors.Mist)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(entry: SourceEntry, openUrl: (String) -> Unit) {
    when (entry) {
        is SourceEntry.Link -> LinkRow(entry.host) { openUrl(entry.url) }
        is SourceEntry.Prose -> ProseRow(entry.text)
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = ArdaasColors.Kesri, modifier = Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_open_in_new),
            contentDescription = null,
            tint = ArdaasColors.Kesri,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ProseRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = ArdaasColors.Mist,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * "1.0 (1)" — version name and build number, iOS's
 * `CFBundleShortVersionString (CFBundleVersion)`. Dashes if the package
 * manager cannot say, as iOS shows for a missing key.
 */
fun appVersionString(context: Context): String {
    val info: PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "Own package info unavailable", e)
        null
    }
    val name = info?.versionName ?: "—"
    val build = info?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            it.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            it.versionCode.toLong()
        }
    }?.toString() ?: "—"
    return "$name ($build)"
}

@Preview
@Composable
private fun SourcesScreenFailedPreview() {
    ArdaasTheme {
        ArdaasBackground {
            SourcesScreen(LoadState.Failed(IllegalStateException("variants.json is not bundled")), "1.0 (1)", onBack = {})
        }
    }
}
