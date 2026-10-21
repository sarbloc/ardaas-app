package io.sarbloc.ardaas.ui.occasion

import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.occasion.Occasion
import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.occasion.OccasionCategory
import io.sarbloc.ardaas.occasion.OccasionChoice
import io.sarbloc.ardaas.occasion.OccasionCopy
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.occasion.OccasionPreview
import io.sarbloc.ardaas.occasion.OccasionSelection
import io.sarbloc.ardaas.ui.ArdaasBackground
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.ArdaasTheme
import io.sarbloc.ardaas.ui.ReaderTextStyles
import io.sarbloc.ardaas.ui.components.GroupedSection
import io.sarbloc.ardaas.ui.components.SectionDivider
import io.sarbloc.ardaas.ui.components.ardaasTopAppBarColors

// Port of Ardaas/Views/OccasionSection.swift (the picker) and
// OccasionEditorSheet.swift (its presentation from the Reader).

/**
 * The "….." slot picker (#90): what the gap is, "None", the bundled catalog
 * grouped by category, "Other…" with free text, and the slot sentence quoted
 * back with the choice spliced in. Shared by Compose and the Reader, so both
 * offer the same rows, wording and warning.
 *
 * A Material 3 **full-screen dialog**: a close (Cancel) button, a title and a
 * Done action over a scrolling list. Full-screen rather than a bottom sheet
 * because this is a task with a text field, not a quick pick — seventeen
 * two-line rows plus free text fill the screen anyway, and a sheet's drag
 * gesture and IME handling fight a focused text field.
 *
 * The picker holds its own draft, seeded from [choice] (a catalog id the
 * [catalog] no longer carries opens as "None", as on iOS), and hands back
 * the result only when the user finishes:
 *
 * * **Done** calls [onDone] with [OccasionDraft.choice] — trimmed free text,
 *   and "Other…" with nothing but whitespace typed is [OccasionChoice.Unset]
 *   (an abandoned draft leaves the canonical dots as authored).
 * * **Cancel** — the close button, system back, or any other dismissal —
 *   calls [onCancel]; the draft is discarded.
 *
 * The host removes the dialog from composition in both callbacks.
 *
 * @param ardaasContent the selected variant's text, for the in-context
 *   preview and the #72 warning. Null omits both (as iOS does while loading).
 */
@Composable
fun OccasionPickerDialog(
    catalog: OccasionCatalog,
    choice: OccasionChoice,
    onDone: (OccasionChoice) -> Unit,
    onCancel: () -> Unit,
    ardaasContent: ArdaasContent? = null,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogWindowSetup()
        OccasionPicker(
            catalog = catalog,
            choice = choice,
            ardaasContent = ardaasContent,
            onDone = onDone,
            onCancel = onCancel,
        )
    }
}

/**
 * The dialog has its own window, which inherits neither the activity's
 * edge-to-edge bar styling nor its keyboard behaviour. The app is always
 * dark, so the bar icons are forced light (as `MainActivity` does), and the
 * window resizes for the keyboard so IME insets reach Compose on every API
 * level.
 */
@Composable
internal fun DialogWindowSetup() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            @Suppress("DEPRECATION") // Still how pre-30 windows learn to resize for the IME.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        onDispose {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OccasionPicker(
    catalog: OccasionCatalog,
    choice: OccasionChoice,
    ardaasContent: ArdaasContent?,
    onDone: (OccasionChoice) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by rememberSaveable(stateSaver = OccasionDraftBundle.saver) {
        mutableStateOf(OccasionDraft.from(choice, catalog))
    }
    val groups = remember(catalog) { catalog.pickerGroups() }
    val focusManager = LocalFocusManager.current
    val customFieldFocus = remember { FocusRequester() }
    // Set when the user picks "Other…", so the field it reveals takes focus
    // once composed — not when the picker merely opens on existing free text.
    var focusCustomField by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val select = { selection: OccasionSelection ->
        if (selection == OccasionSelection.Custom && !draft.isCustom) focusCustomField = true
        if (selection != OccasionSelection.Custom) focusManager.clearFocus()
        draft = draft.withSelection(selection)
    }

    LaunchedEffect(focusCustomField, draft.isCustom) {
        if (focusCustomField && draft.isCustom) {
            customFieldFocus.requestFocus()
            focusCustomField = false
        }
    }

    ArdaasBackground {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            // Transparent has no content colour of its own: name the theme's.
            contentColor = MaterialTheme.colorScheme.onSurface,
            topBar = {
                TopAppBar(
                    title = { Text(OccasionCopy.TITLE) },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            focusManager.clearFocus()
                            onDone(draft.choice)
                        }) { Text("Done") }
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
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    OccasionCopy.EXPLANATION,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArdaasColors.Mist,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                GroupedSection(header = null) {
                    ChoiceRow(selected = draft.selection == OccasionSelection.Unset, onSelect = {
                        select(OccasionSelection.Unset)
                    }) {
                        Text(OccasionCopy.NONE, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                for (group in groups) {
                    GroupedSection(header = group.title) {
                        group.entries.forEachIndexed { index, entry ->
                            if (index > 0) SectionDivider()
                            val selection = OccasionSelection.Catalog(entry.id)
                            ChoiceRow(selected = draft.selection == selection, onSelect = { select(selection) }) {
                                CatalogRowText(entry)
                            }
                        }
                    }
                }

                GroupedSection(header = null) {
                    ChoiceRow(selected = draft.isCustom, onSelect = { select(OccasionSelection.Custom) }) {
                        Text(OccasionCopy.OTHER, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (draft.isCustom) {
                        CustomOccasionField(
                            text = draft.customText,
                            onTextChange = { draft = draft.copy(customText = it) },
                            focusRequester = customFieldFocus,
                            onImeDone = { focusManager.clearFocus() },
                        )
                    }
                }

                if (ardaasContent != null) PreviewSection(draft, catalog, ardaasContent)
            }
        }
    }
}

/** One radio row: the control leading, as Android lists place it, then the label. */
@Composable
private fun ChoiceRow(selected: Boolean, onSelect: () -> Unit, label: @Composable ColumnScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(
            modifier = Modifier.padding(start = 16.dp).weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = label,
        )
    }
}

/**
 * One catalog row: the Gurmukhi as it will appear in the Ardaas, with its
 * English underneath for anyone who doesn't read Gurmukhi.
 */
@Composable
private fun CatalogRowText(entry: Occasion) {
    Text(entry.gurmukhi, style = RowGurmukhiStyle)
    Text(entry.english, style = MaterialTheme.typography.bodySmall, color = ArdaasColors.Mist)
}

/** Free text under "Other…", with the note on how it is placed. */
@Composable
private fun CustomOccasionField(
    text: String,
    onTextChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onImeDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            label = { Text(OccasionCopy.CUSTOM_LABEL) },
            placeholder = { Text(OccasionCopy.CUSTOM_PLACEHOLDER) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onImeDone() }),
        )
        Text(OccasionCopy.FREE_TEXT, style = MaterialTheme.typography.bodySmall, color = ArdaasColors.Mist)
    }
}

/**
 * The slot sentence quoted back with the choice spliced in, and the #72
 * warning — below the picker, as iOS places them. In the scrolling list
 * rather than pinned: the full English sentence runs to several lines, and
 * pinned above the keyboard it would leave no room for the field being typed
 * in on a small phone.
 */
@Composable
private fun PreviewSection(draft: OccasionDraft, catalog: OccasionCatalog, ardaasContent: ArdaasContent) {
    val preview = remember(draft, catalog, ardaasContent) { draft.preview(ardaasContent, catalog) }
    val willNotAppear = remember(draft, catalog, ardaasContent) { draft.willNotAppear(ardaasContent, catalog) }
    if (preview == null && !willNotAppear) return
    GroupedSection(header = null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (preview != null) PreviewText(preview)
            // A choice the text cannot render (#72): said plainly here rather
            // than accepted silently, because the choice is kept either way.
            if (willNotAppear) WillNotAppearNote()
        }
    }
}

@Composable
private fun PreviewText(preview: OccasionPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (preview.isFilled) OccasionCopy.PREVIEW_FILLED else OccasionCopy.PREVIEW_UNFILLED,
            style = MaterialTheme.typography.labelMedium,
            color = ArdaasColors.Mist,
        )
        SelectionContainer { Text(preview.text, style = previewStyle(preview.layer)) }
    }
}

@Composable
private fun WillNotAppearNote() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = ArdaasColors.Kesri,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Text(OccasionCopy.WILL_NOT_APPEAR, style = MaterialTheme.typography.bodySmall, color = ArdaasColors.Kesri)
    }
}

/** Row Gurmukhi: the reading colour at list size, with room for vowel signs above and below. */
private val RowGurmukhiStyle = ReaderTextStyles.Gurmukhi.copy(fontSize = 18.sp, lineHeight = 28.sp)

/**
 * The Reader's layer styles at callout size, so a line looks the same in the
 * preview as it will in the Ardaas itself.
 */
private fun previewStyle(layer: LayerKind): TextStyle = when (layer) {
    LayerKind.GURMUKHI -> ReaderTextStyles.Gurmukhi.copy(fontSize = 17.sp, lineHeight = 28.sp)
    LayerKind.TRANSLITERATION -> ReaderTextStyles.Transliteration.copy(fontSize = 15.sp, lineHeight = 22.sp)
    LayerKind.ENGLISH -> ReaderTextStyles.Translation.copy(fontSize = 15.sp, lineHeight = 22.sp)
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun OccasionPickerPreview() {
    // Placeholder entries, deliberately not scripture: the real list only
    // ever comes from the proof-read bundle, which previews cannot load.
    val catalog = OccasionCatalog(
        version = 1,
        sources = listOf("preview"),
        occasions = listOf(
            Occasion("a", OccasionCategory.PAATH, "ਗੁਰਮੁਖੀ", "gurmukhī", "Preview paath"),
            Occasion("b", OccasionCategory.OCCASION, "ਗੁਰਮੁਖੀ", "gurmukhī", "Preview occasion"),
        ),
    )
    ArdaasTheme {
        OccasionPicker(
            catalog = catalog,
            choice = OccasionChoice.Catalog("a"),
            ardaasContent = null,
            onDone = {},
            onCancel = {},
        )
    }
}
