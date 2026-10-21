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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.content.ArdaasLibrary
import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.ui.ArdaasBackground
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.ArdaasTheme
import io.sarbloc.ardaas.ui.components.GroupedSection
import io.sarbloc.ardaas.ui.components.ardaasTopAppBarColors
import kotlinx.coroutines.launch

// Port of Ardaas/Views/ComposeView.swift, on-device translation included
// (#120): the translate rules live in TranslationStatus, the work in
// ComposeTranslationSession, and the sections in ComposeTranslationSections.

/**
 * Compose: enter a label and a personal benti, pick the variant and the
 * occasion, optionally translate the benti to Gurmukhi on the phone, and
 * save it — as a new [SavedArdaas], or, opened from the Reader's Edit action
 * (#154), over the saved record it was filled from. See [ComposeMode].
 *
 * Sections in iOS order: Label, Ardaas (variant), Occasion, Benti; then
 * Transliteration when the benti is typed in Gurmukhi, or the translate
 * section and its editable Gurmukhi draft when it is English; then the
 * Punjabi keyboard tip.
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
 * @param translation this visit's translation session, retained by the host
 *   (see [rememberComposeTranslationSession]) so a configuration change does
 *   not cancel a download or a translation. Null hides translation entirely
 *   (previews).
 * @param mode a new record, or the saved one being edited. In edit mode the
 *   form opens on the record — seeded once, so a rotation or process death
 *   keeps the user's edits rather than refilling it — the title reads "Edit
 *   Ardaas", and Save hands [onSave] the same row rewritten.
 * @param onSave writes the record [mode] built: an insert when creating, an
 *   update when editing. False (after the host has logged why) keeps the
 *   screen open with the draft intact.
 * @param onClose leaves the screen: after a successful save, or Cancel. When
 *   creating, no confirmation for unsaved input, exactly as iOS's Cancel.
 *   When editing, Cancel and system Back ask "Discard changes?" first if
 *   anything differs from the saved record, and leave at once otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    library: ArdaasLibrary?,
    occasionCatalog: OccasionCatalog?,
    occasion: OccasionDraft,
    onOccasionChange: (OccasionDraft) -> Unit,
    onEditOccasion: (() -> Unit)?,
    translation: ComposeTranslationSession?,
    onSave: suspend (record: SavedArdaas) -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    mode: ComposeMode = ComposeMode.Create,
) {
    // Seeded from the mode once, keyed on the record being edited: a
    // recomposition — or the live record re-emitting after Save — never
    // refills the form over the user's edits, and restored state wins over
    // the seed after a rotation or process death.
    val seedKey = (mode as? ComposeMode.Edit)?.original?.id
    var label by rememberSaveable(seedKey) { mutableStateOf(mode.initialDraft.label) }
    // One saveable value, translatedFrom and isDraftOpen included, so
    // staleness and an emptied-but-open Gurmukhi editor survive a
    // configuration change and process death along with the text.
    var benti by rememberSaveable(seedKey, stateSaver = BentiDraftSaver) { mutableStateOf(mode.initialDraft.benti) }
    var variantId by rememberSaveable(seedKey) { mutableStateOf(mode.initialDraft.variantId) }
    var isSaving by remember { mutableStateOf(false) }
    var showConsent by rememberSaveable { mutableStateOf(false) }
    var showStaleAlert by rememberSaveable { mutableStateOf(false) }
    var showDiscardAlert by rememberSaveable { mutableStateOf(false) }

    val draft = ComposeDraft(label = label, benti = benti, variantId = variantId, occasion = occasion)
    val hasUnsavedChanges = mode.hasUnsavedChanges(draft)
    // Cancel and system Back: straight out, unless editing has changed
    // something — then the user decides.
    val onCancel: () -> Unit = { if (hasUnsavedChanges) showDiscardAlert = true else onClose() }
    val content = library?.resolvedVariant(variantId)?.content
    val status = translation?.let { session ->
        val model by session.modelState.collectAsState()
        session.status(model)
    }

    TranslationEffects(translation, benti, onApply = { benti = it })

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailed = stringResource(R.string.compose_save_failed)
    val saveDraft: (ComposeDraft) -> Unit = { toSave ->
        // Everything is re-read at tap time — `isSaving` and the session are
        // snapshot state — so a second tap, or a translation that started a
        // frame ago, is still refused.
        val record = mode.record(toSave)
        val canSave = translation?.currentStatus()?.canSave(toSave) ?: toSave.canSave
        if (!isSaving && record != null && canSave) {
            isSaving = true
            scope.launch {
                if (onSave(record)) {
                    onClose()
                } else {
                    // Keep the draft and say so.
                    isSaving = false
                    snackbarHostState.showSnackbar(saveFailed)
                }
            }
        }
    }
    // A stale draft would persist Gurmukhi that says something different
    // from the English next to it, and nothing outside this screen can tell
    // that later — so it is never saved without an explicit answer.
    val onSaveTap: () -> Unit = { if (benti.isStale) showStaleAlert = true else saveDraft(draft) }
    // Editing with changes: Back asks, as Cancel does. Declared before the
    // saving handler so that one, composed later, wins while a write runs.
    BackHandler(enabled = hasUnsavedChanges && !isSaving) { showDiscardAlert = true }
    // The write takes milliseconds; Back simply waits for it.
    BackHandler(enabled = isSaving) {}

    val title = if (mode is ComposeMode.Edit) R.string.compose_edit_title else R.string.compose_title
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.imePadding()) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !isSaving) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.compose_cancel))
                    }
                },
                actions = {
                    val canSave = status?.canSave(draft) ?: draft.canSave
                    TextButton(onClick = onSaveTap, enabled = canSave && !isSaving) {
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

            BentiSection(text = benti.typed, onTextChange = { benti = benti.copy(typed = it) })

            if (benti.isTypedInGurmukhi) {
                val transliteration = remember(benti.typed) { benti.transliteration }
                TransliterationSection(transliteration)
            }

            if (translation != null && status != null && status.showsSection(benti)) {
                TranslateSectionView(
                    section = status.section(benti),
                    failure = status.failure,
                    downloadBytes = translation.downloadBytes,
                    actions = TranslateActions(
                        onTranslate = { translation.translate(benti) },
                        onShowConsent = { showConsent = true },
                        onCancelDownload = translation::cancelDownload,
                        onStopWaiting = translation::stopWaiting,
                    ),
                )
                if (status.showsDraft(benti)) {
                    val transliteration = remember(benti.gurmukhi) { benti.transliteration }
                    GurmukhiDraftSection(
                        benti = benti,
                        transliteration = transliteration,
                        showsPurityCaution = TranslationStatus.showsPurityCaution(benti),
                        onGurmukhiChange = { benti = benti.copy(gurmukhi = it) },
                        onRemove = {
                            benti = benti.clearTranslation()
                            translation.clearFailure()
                        },
                    )
                }
            }

            GroupedSection(header = null) {
                KeyboardTip()
            }
        }
    }

    if (translation != null && showConsent) {
        TranslationConsentDialog(
            downloadBytes = translation.downloadBytes,
            peakDiskBytes = translation.peakDiskBytes,
            installedDiskBytes = translation.installedDiskBytes,
            onConfirm = { allowCellular ->
                showConsent = false
                translation.download(allowCellular)
            },
            onDismiss = { showConsent = false },
        )
    }

    if (showStaleAlert) {
        StaleTranslationAlert(
            onSaveBoth = {
                showStaleAlert = false
                saveDraft(draft)
            },
            onSaveEnglishOnly = {
                showStaleAlert = false
                // Built from a value held here, not re-read from the
                // just-written state.
                val cleared = benti.clearTranslation()
                benti = cleared
                saveDraft(draft.copy(benti = cleared))
            },
            onKeepEditing = { showStaleAlert = false },
        )
    }

    if (showDiscardAlert) {
        DiscardChangesAlert(
            onDiscard = {
                showDiscardAlert = false
                onClose()
            },
            onKeepEditing = { showDiscardAlert = false },
        )
    }
}

/**
 * Leaving an edit with changes (#154): Discard in the error colour, Keep
 * editing beside it. Dismissing it (Back, outside tap) is Keep editing.
 */
@Composable
private fun DiscardChangesAlert(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.compose_discard_title)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.compose_discard), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(stringResource(R.string.compose_keep_editing))
            }
        },
    )
}

/**
 * The screen's half of the translation lifecycle; the session holds the
 * rest. Tells the session which draft is on screen (so a translation queued
 * behind an install runs on the current text), writes a finished translation
 * into the draft, and re-warms the translator whenever the screen starts —
 * memory pressure may have released it while the app was in the background.
 */
@Composable
private fun TranslationEffects(
    translation: ComposeTranslationSession?,
    benti: BentiDraft,
    onApply: (BentiDraft) -> Unit,
) {
    if (translation == null) return
    SideEffect { translation.updateDraft(benti) }

    // The draft as of the latest composition, not as of when the effect
    // launched: the user may type in the frame between.
    val currentBenti by rememberUpdatedState(benti)
    val currentOnApply by rememberUpdatedState(onApply)
    val result = translation.pendingResult
    // Keyed on the result, so a screen recreated by a configuration change
    // mid-translation still applies it once it lands.
    LaunchedEffect(result) {
        if (result != null) {
            currentOnApply(currentBenti.applyTranslation(result.gurmukhi, result.source))
            translation.consumeResult(result)
        }
    }

    LifecycleStartEffect(translation) {
        translation.warmUp()
        onStopOrDispose {}
    }
}

/**
 * iOS's stale-save confirmation, as a proper [AlertDialog]: three explicit
 * answers, stacked so none is truncated, the destructive one in the error
 * colour. Dismissing it (Back, outside tap) is Keep editing.
 */
@Composable
private fun StaleTranslationAlert(
    onSaveBoth: () -> Unit,
    onSaveEnglishOnly: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.translation_stale_title)) },
        text = { Text(stringResource(R.string.translation_stale_message)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onSaveBoth) {
                    Text(stringResource(R.string.translation_stale_save_both))
                }
                TextButton(onClick = onSaveEnglishOnly) {
                    Text(stringResource(R.string.translation_stale_save_english), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onKeepEditing) {
                    Text(stringResource(R.string.translation_stale_keep_editing))
                }
            }
        },
    )
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
                translation = null,
                onSave = { true },
                onClose = {},
            )
        }
    }
}
