package io.sarbloc.ardaas.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.translation.BentiTranslationService
import io.sarbloc.ardaas.translation.ModelState
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.ReaderTextStyles
import io.sarbloc.ardaas.ui.components.BackTopAppBar
import io.sarbloc.ardaas.ui.components.GroupedSection
import io.sarbloc.ardaas.ui.components.SectionDivider
import io.sarbloc.ardaas.ui.compose.FormTextField

/**
 * Diagnostics for on-device translation (#119) — the Android analogue of
 * `BentiLabView.swift`. Debug-only (this file lives in `src/debug`). It is
 * *not* the production entry point (#120); everything here is read-outs and
 * manual triggers for measuring latency and memory on a real phone, plus a
 * plain-text report to paste wherever the numbers are being collected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BentiLabScreen(service: BentiTranslationService, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(service) { BentiLabModel(service, context.applicationContext, scope) }
    val state by service.state.collectAsState()
    val installedBytes by service.installedBytes.collectAsState()
    LaunchedEffect(model) { model.refresh() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = { BackTopAppBar("Benti Lab", onBack, scrollBehavior) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            DeviceSection(model)
            ModelSection(model, service, state, installedBytes)
            TranslateSection(model, isReady = state.isReady)
            model.error?.let { ErrorSection(it) }
            model.latestRun?.let { LatestRunSection(it) }
            model.benchmark?.let { BenchmarkSection(it) }
            ReportSection(model)
        }
    }
}

@Composable
private fun DeviceSection(model: BentiLabModel) {
    GroupedSection(header = "Device") {
        LabText(LabReport.deviceLine(model.device))
        SectionDivider()
        LabText(LabReport.ramLine(model.device))
        SectionDivider()
        LabText("Process: ${LabReport.memoryLine(model.memoryNow)}")
        SectionDivider()
        LabButton("Refresh readings", onClick = model::refreshReadings)
    }
}

@Composable
private fun ModelSection(model: BentiLabModel, service: BentiTranslationService, state: ModelState, installedBytes: Long) {
    GroupedSection(header = "Model") {
        LabText("State: ${LabReport.describe(state)} · on disk ${LabReport.mb(installedBytes)}")
        SectionDivider()
        when (state) {
            ModelState.NotDownloaded, is ModelState.Failed -> {
                LabText(
                    "Downloads ${LabReport.mb(service.downloadBytes)} once; needs ${LabReport.mb(service.peakDiskBytes)} " +
                        "free during install, ~${LabReport.mb(service.installedDiskBytes)} after.",
                )
                SectionDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Allow cellular", color = ArdaasColors.Parchment, modifier = Modifier.weight(1f))
                    Switch(checked = model.allowCellular, onCheckedChange = { model.allowCellular = it })
                }
                SectionDivider()
                LabButton(if (state is ModelState.Failed) "Retry download" else "Download model", onClick = model::download)
            }
            is ModelState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.progress.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                LabButton("Cancel download", onClick = model::cancelDownload)
            }
            ModelState.Optimizing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp))
                LabText("Optimizing graphs (one-time)… cannot be interrupted.")
            }
            ModelState.Ready -> LabButton("Release engine from memory", onClick = model::releaseEngine)
        }
        if (installedBytes > 0 && !state.isWorking) {
            SectionDivider()
            LabButton("Delete model", onClick = model::deleteModel, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TranslateSection(model: BentiLabModel, isReady: Boolean) {
    GroupedSection(header = "Translate") {
        BentiLabModel.PRESETS.forEachIndexed { index, preset ->
            if (index > 0) SectionDivider()
            Text(
                preset,
                style = MaterialTheme.typography.bodyMedium,
                color = if (preset == model.input) ArdaasColors.Kesri else ArdaasColors.Mist,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { model.input = preset }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        SectionDivider()
        FormTextField(value = model.input, onValueChange = { model.input = it }, placeholder = "English benti")
        SectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val enabled = isReady && !model.isBusy
            Button(onClick = model::translate, enabled = enabled && model.input.isNotBlank()) { Text("Translate") }
            OutlinedButton(onClick = model::runBenchmark, enabled = enabled) { Text("Run benchmark") }
        }
        if (model.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
}

@Composable
private fun LatestRunSection(run: LabRun) {
    GroupedSection(header = "Last translation") {
        LabText(run.english)
        SectionDivider()
        Text(
            run.gurmukhi,
            style = ReaderTextStyles.Gurmukhi,
            color = ArdaasColors.Parchment,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        SectionDivider()
        LabText(LabReport.runLine(run), monospace = true)
    }
}

@Composable
private fun BenchmarkSection(summary: BenchmarkSummary) {
    GroupedSection(header = "Benchmark") {
        LabText(LabReport.benchmarkLine(summary), monospace = true)
    }
}

@Composable
private fun ErrorSection(message: String) {
    GroupedSection(header = "Error") {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ReportSection(model: BentiLabModel) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    GroupedSection(header = "Results") {
        LabText("${model.runs.size} translation(s) this visit.")
        SectionDivider()
        LabButton("Copy results", onClick = { status = copyToClipboard(context, model.report()) })
        status?.let {
            SectionDivider()
            LabText(it)
        }
    }
}

/** Returns a status line for the screen; the platform shows its own confirmation from Android 13. */
private fun copyToClipboard(context: Context, report: String): String {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (clipboard == null) {
        Log.w("BentiLab", "No ClipboardManager; could not copy the report")
        return "Copy failed: no clipboard service."
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("Benti Lab results", report))
    return "Copied ${report.lines().size} lines."
}

@Composable
private fun LabText(text: String, monospace: Boolean = false) {
    val base = MaterialTheme.typography.bodySmall
    Text(
        text,
        style = if (monospace) base.copy(fontFamily = FontFamily.Monospace) else base,
        color = ArdaasColors.Mist,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun LabButton(label: String, onClick: () -> Unit, color: Color = ArdaasColors.Kesri) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(label, color = color)
    }
}
