package io.sarbloc.ardaas.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.occasion.OccasionCategory
import io.sarbloc.ardaas.occasion.OccasionCopy
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.occasion.OccasionPreview
import io.sarbloc.ardaas.occasion.OccasionSelection
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.components.GroupedSection
import io.sarbloc.ardaas.ui.components.SectionDivider

// Port of the Compose half of Ardaas/Views/OccasionSection.swift: the
// explanation, the Paath and Occasion rows (#158), the "Other…" free text and
// the slot sentence quoted back. The lists themselves are the slot picker
// (#90), opened through [onEditOccasion].

/**
 * What the Paath or Occasion row shows for the current selection — the
 * collapsed form of the picker's rows.
 */
sealed interface OccasionSummary {
    /** Nothing chosen: the slot keeps its dots. */
    data object None : OccasionSummary

    /** A catalog entry: its Gurmukhi, with the English gloss under it. */
    data class Entry(val gurmukhi: String, val english: String) : OccasionSummary

    /** "Other…": the user's own words, typed in the field under the row. */
    data object Other : OccasionSummary

    companion object {
        /**
         * The row's value for [draft]. A catalog id this [catalog] no longer
         * carries reads as [None] — which is also what the text renders.
         */
        fun of(draft: OccasionDraft, catalog: OccasionCatalog): OccasionSummary =
            when (val selection = draft.selection) {
                OccasionSelection.Unset -> None
                OccasionSelection.Custom -> Other
                is OccasionSelection.Catalog ->
                    catalog.occasion(selection.id)?.let { Entry(it.gurmukhi, it.english) } ?: None
            }

        /**
         * The Paath row's value for [draft]: [None], or the paath's entry. An
         * id that is not a bundled paath reads as [None] — which is also what
         * the text renders ([io.sarbloc.ardaas.occasion.SlotChoice.normalised]).
         */
        fun paath(draft: OccasionDraft, catalog: OccasionCatalog): OccasionSummary =
            catalog.occasion(draft.paathId)
                ?.takeIf { it.category == OccasionCategory.PAATH }
                ?.let { Entry(it.gurmukhi, it.english) }
                ?: None
    }
}

/**
 * The "….." slot on the Compose screen.
 *
 * @param content the selected variant's text, which the preview and the #72
 *   warning both follow. Null while the bundle is loading (or if it failed):
 *   both are then omitted, as on iOS.
 * @param onEditOccasion opens the slot picker. Null hides the Paath and
 *   Occasion rows: a row that cannot open anything would be a dead control.
 */
@Composable
internal fun ComposeOccasionSection(
    occasion: OccasionDraft,
    onOccasionChange: (OccasionDraft) -> Unit,
    catalog: OccasionCatalog,
    content: ArdaasContent?,
    onEditOccasion: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val preview = remember(occasion, content, catalog) { content?.let { occasion.preview(it, catalog) } }
    val willNotAppear = remember(occasion, content, catalog) {
        content != null && occasion.willNotAppear(content, catalog)
    }

    GroupedSection(header = stringResource(R.string.compose_occasion_header), modifier = modifier) {
        Footnote(OccasionCopy.EXPLANATION, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

        if (onEditOccasion != null) {
            SectionDivider()
            SummaryRow(stringResource(R.string.compose_paath_row), OccasionSummary.paath(occasion, catalog), onEditOccasion)
            SectionDivider()
            SummaryRow(stringResource(R.string.compose_occasion_row), OccasionSummary.of(occasion, catalog), onEditOccasion)
        }

        if (occasion.isCustom) {
            SectionDivider()
            CustomOccasionField(
                text = occasion.customText,
                onTextChange = { onOccasionChange(occasion.copy(customText = it)) },
            )
            Footnote(OccasionCopy.FREE_TEXT, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }

        if (preview != null) {
            SectionDivider()
            PreviewRow(preview)
        }

        // A choice the text cannot render — see #72. Said plainly rather
        // than accepted silently, because the choice is kept either way.
        if (willNotAppear) {
            SectionDivider()
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = ArdaasColors.Kesri,
                    modifier = Modifier.size(18.dp),
                )
                Text(OccasionCopy.WILL_NOT_APPEAR, style = MaterialTheme.typography.bodySmall, color = ArdaasColors.Kesri)
            }
        }
    }
}

@Composable
private fun SummaryRow(title: String, summary: OccasionSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.compose_occasion_choose),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = ArdaasColors.Parchment,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 220.dp)) {
            when (summary) {
                OccasionSummary.None -> SummaryText(stringResource(R.string.compose_occasion_none))
                OccasionSummary.Other -> SummaryText(stringResource(R.string.compose_occasion_other))
                is OccasionSummary.Entry -> {
                    Text(summary.gurmukhi, style = MaterialTheme.typography.bodyLarge, color = ArdaasColors.Parchment, textAlign = TextAlign.End)
                    Text(summary.english, style = MaterialTheme.typography.bodySmall, color = ArdaasColors.Mist, textAlign = TextAlign.End)
                }
            }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = ArdaasColors.Mist)
    }
}

@Composable
private fun SummaryText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = ArdaasColors.Mist, textAlign = TextAlign.End)
}

@Composable
private fun CustomOccasionField(text: String, onTextChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    FormTextField(
        value = text,
        onValueChange = onTextChange,
        placeholder = stringResource(R.string.compose_occasion_custom_placeholder),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}

/**
 * The slot sentence quoted back, in the layer the choice landed in — the
 * same substitution the Reader performs, so this is a promise the Reader
 * keeps. Styled in the Reader's layer colours.
 */
@Composable
private fun PreviewRow(preview: OccasionPreview) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(
                if (preview.isFilled) R.string.compose_occasion_preview_filled else R.string.compose_occasion_preview_unfilled,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = ArdaasColors.Mist,
        )
        SelectionContainer {
            Text(
                preview.text,
                style = MaterialTheme.typography.bodyLarge,
                color = layerColor(preview.layer),
                fontStyle = if (preview.layer == LayerKind.TRANSLITERATION) FontStyle.Italic else FontStyle.Normal,
            )
        }
    }
}

/** The Reader's layer colours, so a line looks the same here as in the Ardaas itself. */
private fun layerColor(layer: LayerKind): Color = when (layer) {
    LayerKind.GURMUKHI -> ArdaasColors.Parchment
    LayerKind.TRANSLITERATION -> ArdaasColors.Sand
    LayerKind.ENGLISH -> ArdaasColors.Mist
}
