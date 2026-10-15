package io.sarbloc.ardaas.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.content.ArdaasLibrary
import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.ui.ArdaasBackground
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.ArdaasTheme
import io.sarbloc.ardaas.ui.ReaderTextStyles
import io.sarbloc.ardaas.ui.components.GroupedSection
import io.sarbloc.ardaas.ui.components.SectionDivider
import io.sarbloc.ardaas.ui.components.ardaasTopAppBarColors
import kotlinx.coroutines.launch

// Port of Ardaas/Views/ComposeView.swift, without its translation engine (v1
// Android has none): the user types Gurmukhi directly instead.

/**
 * Compose: enter a label and a personal benti, pick the variant and the
 * occasion, and save it as a new [SavedArdaas]. Create-only, as on iOS —
 * editing an existing record is the Reader's job.
 *
 * Sections in iOS order: Label, Ardaas (variant), Occasion, Benti, then
 * Gurmukhi — the direct-entry editor plus the Punjabi keyboard tip.
 *
 * Takes already-loaded inputs; loading the bundle and persisting the record
 * belong to the host.
 *
 * @param library null while the bundle loads or after it failed: the variant
 *   picker and the occasion preview hide, and Save uses the default variant
 *   (a broken bundle already surfaces in the Reader) — iOS's rule.
 * @param occasionCatalog null hides the Occasion section entirely, leaving
 *   the slot unset, which renders the canonical dots.
 * @param occasion the occasion being chosen. Hoisted — the host owns it — so
 *   the occasion picker the host presents can write the choice back through
 *   [onOccasionChange]. The screen itself writes it only from the "Other…"
 *   free-text field.
 * @param onEditOccasion opens the occasion picker. Null hides the "What it's
 *   for" row.
 * @param onSave inserts the record; false (after the host has logged why)
 *   keeps the screen open with the draft intact.
 * @param onClose leaves the screen: after a successful save, or Cancel. No
 *   confirmation for unsaved input, exactly as iOS's Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    library: ArdaasLibrary?,
    occasionCatalog: OccasionCatalog?,
    occasion: OccasionDraft,
    onOccasionChange: (OccasionDraft) -> Unit,
    onEditOccasion: (() -> Unit)?,
    onSave: suspend (SavedArdaas) -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var bentiTyped by rememberSaveable { mutableStateOf("") }
    var bentiGurmukhi by rememberSaveable { mutableStateOf("") }
    var variantId by rememberSaveable { mutableStateOf(ArdaasLibrary.DEFAULT_VARIANT_ID) }
    var isSaving by remember { mutableStateOf(false) }

    val benti = BentiDraft(typed = bentiTyped, gurmukhi = bentiGurmukhi)
    val draft = ComposeDraft(label = label, benti = benti, variantId = variantId, occasion = occasion)
    val content = library?.resolvedVariant(variantId)?.content

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailed = stringResource(R.string.compose_save_failed)
    val save: () -> Unit = save@{
        if (isSaving) return@save
        val record = draft.toSavedArdaas() ?: return@save
        isSaving = true
        scope.launch {
            if (onSave(record)) {
                onClose()
            } else {
                isSaving = false
                snackbarHostState.showSnackbar(saveFailed)
            }
        }
    }
    // Leaving mid-insert would cancel it; the write takes milliseconds, so
    // Back simply waits for it.
    BackHandler(enabled = isSaving) {}

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.imePadding()) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !isSaving) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.compose_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = save, enabled = draft.canSave && !isSaving) {
                        Text(stringResource(R.string.compose_save), fontWeight = FontWeight.SemiBold)
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
                // The bars' insets are already in innerPadding, so only the
                // part of the keyboard that rises above them shrinks the
                // viewport — and the focused field scrolls clear of it.
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                // Inside the scroll, so the form scrolls under the bars
                // edge to edge and still rests clear of them.
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            LabelSection(label = label, onLabelChange = { label = it })

            if (library != null) {
                VariantSection(library = library, selectedId = variantId, onSelect = { variantId = it })
            }

            // Directly under the variant picker because the two belong
            // together: the preview and its warning follow the variant.
            if (occasionCatalog != null) {
                ComposeOccasionSection(
                    occasion = occasion,
                    onOccasionChange = onOccasionChange,
                    catalog = occasionCatalog,
                    content = content,
                    onEditOccasion = onEditOccasion,
                )
            }

            BentiSection(text = bentiTyped, onTextChange = { bentiTyped = it })

            GurmukhiSection(benti = benti, onGurmukhiChange = { bentiGurmukhi = it })
        }
    }
}

@Composable
private fun LabelSection(label: String, onLabelChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    GroupedSection(header = stringResource(R.string.compose_label_header)) {
        FormTextField(
            value = label,
            onValueChange = onLabelChange,
            placeholder = stringResource(R.string.compose_label_placeholder),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
    }
}

/** iOS's menu-style variant `Picker`: the row shows the selection, a tap drops the list. */
@Composable
private fun VariantSection(library: ArdaasLibrary, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = library.resolvedVariant(selectedId)
    GroupedSection(header = stringResource(R.string.compose_variant_header)) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = stringResource(R.string.compose_variant_choose),
                        role = Role.DropdownList,
                    ) { expanded = true }
                    .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.compose_variant_row),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ArdaasColors.Parchment,
                    modifier = Modifier.weight(1f),
                )
                Text(selected.displayName, style = MaterialTheme.typography.bodyLarge, color = ArdaasColors.Kesri)
                Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = ArdaasColors.Kesri)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (variant in library.variants) {
                    DropdownMenuItem(
                        text = { Text(variant.displayName) },
                        onClick = {
                            expanded = false
                            onSelect(variant.id)
                        },
                        leadingIcon = { RadioButton(selected = variant.id == selected.id, onClick = null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BentiSection(text: String, onTextChange: (String) -> Unit) {
    GroupedSection(header = stringResource(R.string.compose_benti_header)) {
        FormTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = stringResource(R.string.compose_benti_placeholder),
            minHeight = 160.dp,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

/**
 * Direct Gurmukhi entry, where iOS offers machine translation. When the benti
 * editor already holds Gurmukhi, that text *is* the Gurmukhi layer
 * ([BentiDraft.isTypedInGurmukhi]) and this editor would be ignored, so it is
 * replaced by a note saying so — iOS likewise hides its Gurmukhi draft then.
 * Either way the generated transliteration is shown, and the keyboard tip
 * closes the section.
 */
@Composable
private fun GurmukhiSection(benti: BentiDraft, onGurmukhiChange: (String) -> Unit) {
    val transliteration = remember(benti) { benti.transliteration }
    GroupedSection(header = stringResource(R.string.compose_gurmukhi_header)) {
        if (benti.isTypedInGurmukhi) {
            Footnote(
                stringResource(R.string.compose_gurmukhi_typed_in_benti),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            FormTextField(
                value = benti.gurmukhi,
                onValueChange = onGurmukhiChange,
                placeholder = stringResource(R.string.compose_gurmukhi_placeholder),
                minHeight = 110.dp,
                textStyle = ReaderTextStyles.Gurmukhi.copy(fontSize = ReaderTextStyles.Gurmukhi.fontSize * 0.9f),
                // Asks the keyboard for Punjabi; Gboard switches to it when
                // the user has added it.
                keyboardOptions = KeyboardOptions(hintLocales = LocaleList("pa-IN")),
            )
        }
        if (transliteration.isNotEmpty()) {
            SectionDivider()
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SelectionContainer {
                    Text(
                        transliteration,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = ArdaasColors.Sand,
                    )
                }
                Footnote(stringResource(R.string.compose_transliteration_note))
            }
        }
        SectionDivider()
        KeyboardTip()
    }
}

@Composable
private fun KeyboardTip() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_keyboard),
            contentDescription = null,
            tint = ArdaasColors.Mist,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.compose_keyboard_tip_title),
                style = MaterialTheme.typography.titleSmall,
                color = ArdaasColors.Parchment,
            )
            Footnote(stringResource(R.string.compose_keyboard_tip_body))
        }
    }
}

/** iOS's `.footnote` in mist: explanatory copy under a control. */
@Composable
internal fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = ArdaasColors.Mist, modifier = modifier)
}

/**
 * A text field drawn as a row of a [GroupedSection] — iOS's `TextField` /
 * `TextEditor` in a `Form`: no box or underline of its own, the card is the
 * field.
 */
@Composable
internal fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minHeight: Dp? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .then(if (minHeight != null) Modifier.heightIn(min = minHeight) else Modifier),
        placeholder = { Text(placeholder, style = textStyle.copy(color = ArdaasColors.Mist)) },
        singleLine = singleLine,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = ArdaasColors.Parchment,
            unfocusedTextColor = ArdaasColors.Parchment,
            cursorColor = ArdaasColors.Kesri,
            focusedPlaceholderColor = ArdaasColors.Mist,
            unfocusedPlaceholderColor = ArdaasColors.Mist,
        ),
    )
}

@Preview
@Composable
private fun ComposeScreenPreview() {
    ArdaasTheme {
        ArdaasBackground {
            ComposeScreen(
                library = null,
                occasionCatalog = null,
                occasion = OccasionDraft(),
                onOccasionChange = {},
                onEditOccasion = null,
                onSave = { true },
                onClose = {},
            )
        }
    }
}
