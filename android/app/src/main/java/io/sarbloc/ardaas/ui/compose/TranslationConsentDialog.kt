package io.sarbloc.ardaas.ui.compose

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.ui.ArdaasBackground
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.ArdaasTheme
import io.sarbloc.ardaas.ui.components.GroupedSection
import io.sarbloc.ardaas.ui.components.SectionDivider
import io.sarbloc.ardaas.ui.components.ardaasTopAppBarColors
import io.sarbloc.ardaas.ui.occasion.DialogWindowSetup

// Port of Ardaas/Views/TranslationConsentView.swift.

/**
 * The one-time explanation shown before a single byte is downloaded.
 *
 * The translation model is ~359 MB and needs ~1 GB of free space to install,
 * so it is never fetched behind the user's back: tapping Translate with no
 * model installed shows this, and only its Download button leads to
 * `BentiTranslationService.download`.
 *
 * Deliberately dumb — it owns no service and starts nothing. It takes the
 * three byte figures (from the pinned catalog, not an estimate of the
 * download) and hands back the one decision it collects: whether mobile data
 * is allowed, off by default.
 *
 * A full-screen dialog, as the occasion picker is — iOS presents this as a
 * sheet with a Not now button; here that is the close button, and system
 * Back does the same.
 *
 * @param onConfirm called with the mobile-data choice when the user taps
 *   Download. The host removes the dialog.
 * @param onDismiss Not now, Back, or any other dismissal: nothing starts.
 */
@Composable
fun TranslationConsentDialog(
    downloadBytes: Long,
    peakDiskBytes: Long,
    installedDiskBytes: Long,
    onConfirm: (allowCellular: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogWindowSetup()
        TranslationConsent(downloadBytes, peakDiskBytes, installedDiskBytes, onConfirm, onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationConsent(
    downloadBytes: Long,
    peakDiskBytes: Long,
    installedDiskBytes: Long,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Saveable, so a configuration change does not quietly reset the choice.
    var allowCellular by rememberSaveable { mutableStateOf(false) }
    val download = fileSize(downloadBytes)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    ArdaasBackground {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.consent_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.consent_not_now),
                            )
                        }
                    },
                    colors = ardaasTopAppBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                GroupedSection(header = null) {
                    Text(
                        stringResource(R.string.consent_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ArdaasColors.Parchment,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                GroupedSection(header = stringResource(R.string.consent_sizes_header)) {
                    SizeRow(stringResource(R.string.consent_size_download), stringResource(R.string.consent_size_download_value, download))
                    SectionDivider()
                    SizeRow(stringResource(R.string.consent_size_peak), fileSize(peakDiskBytes))
                    SectionDivider()
                    SizeRow(stringResource(R.string.consent_size_installed), fileSize(installedDiskBytes))
                }

                GroupedSection(header = stringResource(R.string.consent_privacy_header)) {
                    Point(R.drawable.ic_lock, stringResource(R.string.consent_privacy_body))
                }

                GroupedSection(header = stringResource(R.string.consent_network_header)) {
                    Point(R.drawable.ic_wifi, stringResource(R.string.consent_network_body, download))
                    SectionDivider()
                    CellularToggle(checked = allowCellular, onCheckedChange = { allowCellular = it })
                    SectionDivider()
                    Point(R.drawable.ic_hourglass, stringResource(R.string.consent_keep_open))
                }

                Button(
                    onClick = { onConfirm(allowCellular) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.consent_download, download), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** One size figure: label leading, value trailing — read as one by TalkBack. */
@Composable
private fun SizeRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ArdaasColors.Mist, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = ArdaasColors.Parchment)
    }
}

/** An explanatory point with a decorative kesri icon. */
@Composable
private fun Point(icon: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = ArdaasColors.Kesri, modifier = Modifier.size(20.dp))
        Footnote(text)
    }
}

/** The whole row toggles, so the switch's label is its accessible name. */
@Composable
private fun CellularToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.consent_cellular_toggle),
            style = MaterialTheme.typography.bodyMedium,
            color = ArdaasColors.Parchment,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Bytes as the platform shows storage to the user (Settings → Storage):
 * decimal units, matching iOS's `ByteCountFormatter(.file)`.
 */
@Composable
internal fun fileSize(bytes: Long): String = Formatter.formatFileSize(LocalContext.current, bytes)

@Preview
@Composable
private fun TranslationConsentPreview() {
    ArdaasTheme {
        TranslationConsent(
            downloadBytes = 358_521_397,
            peakDiskBytes = 1_028_521_397,
            installedDiskBytes = 676_770_640,
            onConfirm = {},
            onDismiss = {},
        )
    }
}
