package io.sarbloc.ardaas.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.translation.TranslationError
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.ReaderTextStyles
import io.sarbloc.ardaas.ui.components.GroupedSection
import io.sarbloc.ardaas.ui.components.SectionDivider

// The translate-related sections of Compose — iOS ComposeView's
// `transliterationSection`, `translateSection` and `gurmukhiDraftSection`.
// Layout only: what each shows is decided by TranslationStatus.

/**
 * Typed straight in Gurmukhi: no model involved — the transliteration is
 * generated from the typed text by pure rules, so this renders offline, with
 * nothing installed.
 */
@Composable
internal fun TransliterationSection(transliteration: String) {
    GroupedSection(header = stringResource(R.string.compose_transliteration_header)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TransliterationText(transliteration, MaterialTheme.typography.bodyMedium)
            Footnote(stringResource(R.string.compose_transliteration_note))
        }
    }
}

/** Callbacks of the translate section, grouped so the composable stays readable. */
internal class TranslateActions(
    val onTranslate: () -> Unit,
    val onShowConsent: () -> Unit,
    val onCancelDownload: () -> Unit,
    val onStopWaiting: () -> Unit,
)

/**
 * The "Gurmukhi" section: whatever [section] says the model's state calls
 * for, then the inline translation failure if there is one.
 */
@Composable
internal fun TranslateSectionView(
    section: TranslateSection,
    failure: TranslateFailure?,
    downloadBytes: Long,
    actions: TranslateActions,
) {
    GroupedSection(header = stringResource(R.string.translation_section_header)) {
        when (section) {
            is TranslateSection.Offer -> {
                TranslateButtonRow(
                    section.button,
                    onClick = if (section.button.opensConsent) actions.onShowConsent else actions.onTranslate,
                )
                SectionDivider()
                RowFootnote(
                    if (section.needsDownload) {
                        stringResource(R.string.translation_first_download_note, fileSize(downloadBytes))
                    } else {
                        stringResource(R.string.translation_on_device_note)
                    },
                )
            }
            is TranslateSection.Downloading -> {
                DownloadProgress(section)
                SectionDivider()
                RowFootnote(
                    stringResource(
                        if (section.isAwaitingTranslation) R.string.translation_keep_open_awaiting else R.string.translation_keep_open,
                    ),
                )
                SectionDivider()
                RowButton(stringResource(R.string.translation_cancel_download), onClick = actions.onCancelDownload)
            }
            is TranslateSection.Preparing -> {
                Preparing()
                // Optimization cannot be interrupted, so this does not stop
                // it — it drops the queued translate Save is waiting on.
                if (section.offersStopWaiting) {
                    SectionDivider()
                    RowButton(stringResource(R.string.translation_stop_waiting), onClick = actions.onStopWaiting)
                }
            }
            is TranslateSection.Failed -> {
                ErrorRow(errorText(section.error))
                SectionDivider()
                RowButton(stringResource(R.string.translation_try_again), onClick = actions.onShowConsent)
            }
        }
        if (failure != null) {
            SectionDivider()
            ErrorRow(failureText(failure))
        }
    }
}

@Composable
private fun TranslateButtonRow(button: TranslateButton, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = button.isEnabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (button.label == TranslateButton.Label.TRANSLATING) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(painterResource(R.drawable.ic_translate), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(
                stringResource(
                    when (button.label) {
                        TranslateButton.Label.TRANSLATE -> R.string.translation_translate
                        TranslateButton.Label.TRANSLATE_AGAIN -> R.string.translation_translate_again
                        TranslateButton.Label.TRANSLATING -> R.string.translation_translating
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                // Announced when the label flips to "Translating…" and back.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Percent and bar read as one element. The bar carries its own range info,
 * so TalkBack reports progress when focused without a live region chattering
 * every percent.
 */
@Composable
private fun DownloadProgress(section: TranslateSection.Downloading) {
    val label = stringResource(R.string.translation_downloading, section.percent)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Footnote(label)
        LinearProgressIndicator(progress = { section.progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Preparing() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Footnote(stringResource(R.string.translation_preparing))
    }
}

/**
 * The reviewable draft: editable Gurmukhi, its regenerated transliteration,
 * and an honest disclaimer about where it came from — plus the purity
 * caution and the staleness note when they apply.
 */
@Composable
internal fun GurmukhiDraftSection(
    benti: BentiDraft,
    transliteration: String,
    showsPurityCaution: Boolean,
    onGurmukhiChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    GroupedSection(header = stringResource(R.string.translation_draft_header)) {
        val editorLabel = stringResource(R.string.translation_draft_editor)
        FormTextField(
            value = benti.gurmukhi,
            onValueChange = onGurmukhiChange,
            placeholder = "",
            modifier = Modifier.semantics { contentDescription = editorLabel },
            minHeight = 110.dp,
            textStyle = ReaderTextStyles.Gurmukhi.copy(fontSize = ReaderTextStyles.Gurmukhi.fontSize * 0.9f),
            // Asks the keyboard for Punjabi; Gboard switches to it when the
            // user has added it.
            keyboardOptions = KeyboardOptions(hintLocales = LocaleList("pa-IN")),
        )
        if (transliteration.isNotEmpty()) {
            SectionDivider()
            val label = stringResource(R.string.translation_draft_transliteration)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                TransliterationText(
                    transliteration,
                    MaterialTheme.typography.bodySmall,
                    Modifier.semantics { contentDescription = "$label: $transliteration" },
                )
            }
        }
        SectionDivider()
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CautionTitle(stringResource(R.string.translation_machine_title))
            Footnote(stringResource(R.string.translation_machine_body))
        }
        if (showsPurityCaution) {
            SectionDivider()
            CautionNote(R.drawable.ic_warning, stringResource(R.string.translation_purity_caution))
        }
        if (benti.isStale) {
            SectionDivider()
            CautionNote(R.drawable.ic_sync, stringResource(R.string.translation_stale_note))
        }
        SectionDivider()
        RowButton(stringResource(R.string.translation_remove), onClick = onRemove, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TransliterationText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Text(text, style = style, fontStyle = FontStyle.Italic, color = ArdaasColors.Sand)
    }
}

@Composable
private fun CautionTitle(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = ArdaasColors.Kesri, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ArdaasColors.Kesri)
    }
}

@Composable
private fun CautionNote(icon: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = ArdaasColors.Kesri, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = ArdaasColors.Kesri)
    }
}

/** A failure, in the error colour, announced when it appears. */
@Composable
private fun ErrorRow(text: String) {
    val color = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun RowFootnote(text: String) {
    Footnote(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

/** A secondary action drawn as a row of the card, as iOS's in-Form buttons are. */
@Composable
private fun RowButton(text: String, onClick: () -> Unit, color: Color = Color.Unspecified) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun errorText(error: TranslationError): String =
    translationErrorMessage(error)?.let { stringResource(it) } ?: (error.message ?: error.toString())

@Composable
private fun failureText(failure: TranslateFailure): String = when (failure) {
    TranslateFailure.EmptyResult -> stringResource(R.string.translation_error_empty)
    is TranslateFailure.Failed -> errorText(failure.error)
}
