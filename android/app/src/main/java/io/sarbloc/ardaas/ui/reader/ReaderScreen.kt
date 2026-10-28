package io.sarbloc.ardaas.ui.reader

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.composer.BentiLayers
import io.sarbloc.ardaas.composer.BentiLine
import io.sarbloc.ardaas.composer.LayerAvailability
import io.sarbloc.ardaas.composer.LayerToggles
import io.sarbloc.ardaas.composer.RenderItem
import io.sarbloc.ardaas.composer.visibleLines
import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.ArdaasLibrary
import io.sarbloc.ardaas.content.ArdaasSegment
import io.sarbloc.ardaas.content.ArdaasVariant
import io.sarbloc.ardaas.content.BentiSlot
import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.occasion.OccasionCopy
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.persistence.bentiLayers
import io.sarbloc.ardaas.persistence.renderItems
import io.sarbloc.ardaas.persistence.slotChoice
import io.sarbloc.ardaas.ui.ArdaasBackground
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.ArdaasTheme
import java.util.WeakHashMap
import kotlinx.coroutines.delay

// Port of Ardaas/Views/ReaderView.swift.

/**
 * Reads a saved Ardaas in situ: the canonical segments with the personal
 * benti composed in at the bundled slot and the chosen occasion spliced into
 * the "….." — three layers per segment, each in its reading colour.
 *
 * Takes already-loaded inputs; loading the bundle (and surfacing a failure to
 * load it) belongs to the host, as does persisting the record.
 *
 * @param library the bundled variants. The record's variant resolves through
 *   it, falling back to the default for an id a later bundle retired.
 * @param occasionCatalog null when the catalog failed to load: the "….." then
 *   stays as authored (free text still lands) and the occasion button hides.
 *   The Ardaas still reads, which is what this screen is for.
 * @param onBack the top bar's back arrow. System back is the host's.
 * @param onVariantChange the display menu's variant picker, which persists
 *   (operator decision, matching iOS) — the host writes the id to the record
 *   and recomposes with it. Also called once to heal a record whose variant
 *   was removed from the bundle, so the stored id, the rendered text and the
 *   picker all agree.
 * @param onEditOccasion opens the occasion picker (#90). Null hides the
 *   Occasion button and leaves the stranded-occasion note informational.
 * @param onEdit opens Compose on this record (#154). Null hides the Edit
 *   button.
 * @param onDuplicate saves a copy of this record and opens it in this
 *   Reader's place (#159). Null hides the Duplicate button.
 */
@Composable
fun ReaderScreen(
    savedArdaas: SavedArdaas,
    library: ArdaasLibrary,
    occasionCatalog: OccasionCatalog?,
    onBack: () -> Unit,
    onVariantChange: (variantId: String) -> Unit,
    modifier: Modifier = Modifier,
    onEditOccasion: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val preferences = remember { ReaderPreferencesState(ReaderPreferenceStore.from(context.applicationContext)) }

    KeepScreenOn()

    LaunchedEffect(savedArdaas.variantId, library) {
        if (library.variant(savedArdaas.variantId) == null) onVariantChange(library.defaultVariant.id)
    }

    ReaderContent(
        savedArdaas = savedArdaas,
        library = library,
        occasionCatalog = occasionCatalog,
        preferences = preferences,
        onBack = onBack,
        onVariantChange = onVariantChange,
        onEditOccasion = onEditOccasion,
        onEdit = onEdit,
        onDuplicate = onDuplicate,
        modifier = modifier,
    )
}

/** The screen stays lit while it is shown — a reader mid-recitation must not lose the text. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        KeepScreenOnHolds.acquire(view)
        onDispose { KeepScreenOnHolds.release(view) }
    }
}

/**
 * Counts the Readers holding a view awake. Two overlap while one replaces
 * the other — a duplicate opening in its original's place (#159) crossfades
 * between them — and the outgoing one letting go must not switch the screen
 * off under the incoming one. Main thread only, as composition effects are.
 */
internal object KeepScreenOnHolds {
    private val holds = WeakHashMap<View, Int>()

    fun acquire(view: View) {
        holds[view] = (holds[view] ?: 0) + 1
        view.keepScreenOn = true
    }

    fun release(view: View) {
        val remaining = (holds[view] ?: 1) - 1
        if (remaining > 0) {
            holds[view] = remaining
        } else {
            holds.remove(view)
            view.keepScreenOn = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    savedArdaas: SavedArdaas,
    library: ArdaasLibrary,
    occasionCatalog: OccasionCatalog?,
    preferences: ReaderPreferencesState,
    onBack: () -> Unit,
    onVariantChange: (String) -> Unit,
    onEditOccasion: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDuplicate: (() -> Unit)?,
    modifier: Modifier,
) {
    val variant = library.resolvedVariant(savedArdaas.variantId)
    val content = variant.content
    val benti = remember(savedArdaas) { savedArdaas.bentiLayers }
    val toggles = preferences.toggles

    // The whole composition — benti and occasion both — is one call on the
    // record, so what this screen renders is exactly what the tests assert.
    val items = remember(savedArdaas, content, occasionCatalog) { savedArdaas.renderItems(content, occasionCatalog) }
    val canonicalLayers = remember(content, toggles) { LayerAvailability.canonical(content).renderedLayers(toggles) }
    val screenAvailability = remember(content, benti) { LayerAvailability.screen(content, benti) }
    val styles = remember(preferences.fontScale) { ScaledReaderStyles.at(preferences.fontScale) }
    val occasionSlotId = content.occasionSlot?.inSegmentId
    // The chosen occasion lands in no layer this variant carries (#72) —
    // reachable by switching variants here — so the reader is looking at the
    // dots and would have no idea why. Said at the slot itself.
    val occasionStranded = remember(savedArdaas, content, occasionCatalog) {
        // The occasion as the slot reads it — a legacy paath stored as the
        // occasion has moved out of it ([SlotChoice.normalised]).
        occasionCatalog != null &&
            savedArdaas.slotChoice.normalised(occasionCatalog).occasion.willNotAppear(content, occasionCatalog)
    }
    // Only when there is something to pick *and* somewhere to put it.
    val editOccasion = onEditOccasion?.takeIf { occasionCatalog != null && content.occasionSlot != null }

    val isPinned = { kind: LayerKind -> isPinnedLayer(kind, toggles, content, benti) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    ArdaasBackground(modifier) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(savedArdaas.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Its own control, ahead of the cog: what the prayer
                        // is for is content, not a display preference.
                        if (editOccasion != null) {
                            IconButton(onClick = editOccasion) {
                                Icon(painterResource(R.drawable.ic_tag), contentDescription = OccasionCopy.TITLE)
                            }
                        }
                        DisplayMenu(
                            library = library,
                            selectedVariantId = variant.id,
                            toggles = toggles,
                            fontScale = preferences.fontScale,
                            isGurmukhiPinned = isPinned(LayerKind.GURMUKHI),
                            onVariantChange = onVariantChange,
                            onGurmukhiChange = { preferences.setLayer(LayerKind.GURMUKHI, it) },
                            onAdjustFontScale = preferences::adjustFontScale,
                            onEdit = onEdit,
                            onDuplicate = onDuplicate,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                LayerPillBar(
                    availability = screenAvailability,
                    toggles = toggles,
                    isPinned = isPinned,
                    onToggle = preferences::setLayer,
                )
            },
        ) { innerPadding ->
            val direction = LocalLayoutDirection.current
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                // Padding, not a padded modifier, so the text scrolls behind
                // the bars edge to edge and still rests clear of them.
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(direction) + 20.dp,
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    end = innerPadding.calculateEndPadding(direction) + 20.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(
                    items = items,
                    key = { item ->
                        when (item) {
                            is RenderItem.Canonical -> "segment:${item.segment.id}"
                            is RenderItem.Benti -> "benti"
                        }
                    },
                    contentType = { item -> item::class },
                ) { item ->
                    when (item) {
                        is RenderItem.Canonical -> SegmentRow(
                            segment = item.segment,
                            layers = canonicalLayers,
                            styles = styles,
                            strandedOccasion = occasionStranded && item.segment.id == occasionSlotId,
                            onEditOccasion = editOccasion,
                        )
                        is RenderItem.Benti -> BentiCard(item.layers.visibleLines(toggles), styles)
                    }
                }
            }
        }
    }
}

// region Rows

/**
 * One canonical segment. [layers] carries the never-blank guard
 * ([LayerAvailability.renderedLayers]), reasoned about the variant alone.
 */
@Composable
private fun SegmentRow(
    segment: ArdaasSegment,
    layers: Set<LayerKind>,
    styles: ScaledReaderStyles,
    strandedOccasion: Boolean,
    onEditOccasion: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (kind in LayerKind.entries) {
            if (kind !in layers) continue
            val text = segment.text(kind) ?: continue
            Text(text, style = styles.forLayer(kind))
        }
        if (strandedOccasion) StrandedOccasionNote(onEditOccasion)
    }
}

/**
 * The #72 warning at the slot, in the picker's own words. Opens the picker
 * when there is one, so it can be fixed where it is read.
 */
@Composable
private fun StrandedOccasionNote(onEditOccasion: (() -> Unit)?) {
    val clickable = if (onEditOccasion == null) {
        Modifier
    } else {
        Modifier.clickable(onClickLabel = "Open the paath and occasion picker", role = Role.Button, onClick = onEditOccasion)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).then(clickable),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = ArdaasColors.Kesri,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Text(OccasionCopy.WILL_NOT_APPEAR, style = MaterialTheme.typography.bodySmall, color = ArdaasColors.Kesri)
    }
}

/**
 * The benti, layer by layer exactly as a canonical segment is — same type,
 * colours and spacing — inside the kesri-tinted card that marks it out as the
 * user's own words. [lines] is [visibleLines]'s decision, never-blank fallback
 * included, so the card is never drawn empty.
 */
@Composable
private fun BentiCard(lines: List<BentiLine>, styles: ScaledReaderStyles) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ArdaasColors.Kesri.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (line in lines) Text(line.text, style = styles.forLayer(line.kind))
        }
        // The kesri leading edge.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 6.dp)
                .wrapContentWidth(Alignment.Start)
                .width(3.dp)
                .background(ArdaasColors.Kesri.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        )
    }
}

// endregion

// region Layer toggles

/**
 * Independent on/off pills for the two secondary layers (#169): two separate
 * floating capsules, solid kesri when on, so each reads as its own switch
 * rather than one two-way control. No bar behind them — the text scrolls
 * underneath, and the Scaffold's inner padding keeps the last line clear.
 * Gurmukhi's toggle stays in the display menu: it's the anchor layer,
 * switched rarely.
 *
 * Pills only for layers something on screen actually carries — the variant or
 * the benti — so the user's own words stay controllable on a variant without
 * that layer. No pills at all when neither carries either layer.
 */
@Composable
private fun LayerPillBar(
    availability: LayerAvailability,
    toggles: LayerToggles,
    isPinned: (LayerKind) -> Boolean,
    onToggle: (LayerKind, Boolean) -> Unit,
) {
    if (!availability.transliteration && !availability.english) return
    // "Transliteration off" etc., for a moment: the glyphs are compact, and
    // this is how a first-time reader learns which is which. A counter, not
    // the text, keys the timer so a repeat of the same toggle restarts it.
    var hint by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(hint?.first) {
        if (hint != null) {
            delay(1500)
            hint = null
        }
    }
    val toggle = { kind: LayerKind, on: Boolean ->
        onToggle(kind, on)
        hint = ((hint?.first ?: 0) + 1) to "${kind.pillTitle} ${if (on) "on" else "off"}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A soft fade, not a bar: the text dims as it passes under the
            // pills instead of competing with them.
            .background(Brush.verticalGradient(listOf(Color.Transparent, ArdaasColors.NavyBottom.copy(alpha = 0.92f))))
            .navigationBarsPadding()
            .padding(top = 20.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(visible = hint != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = hint?.second.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = ArdaasColors.Parchment,
                modifier = Modifier
                    .background(PillOffFill, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    // TalkBack already announces the toggle's new state.
                    .clearAndSetSemantics {},
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (availability.transliteration) {
                LayerPill(LayerKind.TRANSLITERATION, toggles, isPinned, toggle)
            }
            if (availability.english) {
                LayerPill(LayerKind.ENGLISH, toggles, isPinned, toggle)
            }
        }
    }
}

/** The off pill's fill: translucent navy, so the text shows through faintly. */
private val PillOffFill = Color(0xD9283454)

@Composable
private fun LayerPill(
    kind: LayerKind,
    toggles: LayerToggles,
    isPinned: (LayerKind) -> Boolean,
    onToggle: (LayerKind, Boolean) -> Unit,
) {
    val isOn = toggles[kind]
    val enabled = !isPinned(kind)
    Box(
        modifier = Modifier
            // A 48dp touch target around the 34dp pill.
            .sizeIn(minHeight = 48.dp)
            // The glyph is shorthand; TalkBack gets the full name. Ahead of
            // the toggleable so both land on the one focusable node.
            .semantics { contentDescription = kind.pillTitle }
            .toggleable(value = isOn, enabled = enabled, role = Role.Switch, onValueChange = { onToggle(kind, it) })
            // Disabled reads as dimmed, as a disabled iOS button does.
            .alpha(if (enabled) 1f else 0.55f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = kind.pillGlyph,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = if (isOn) ArdaasColors.NavyBottom else ArdaasColors.Mist,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .shadow(8.dp, CircleShape)
                .then(
                    if (isOn) {
                        Modifier.background(ArdaasColors.Kesri, CircleShape)
                    } else {
                        Modifier
                            .background(PillOffFill, CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                    },
                )
                .sizeIn(minWidth = 52.dp, minHeight = 34.dp)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 12.dp)
                .clearAndSetSemantics {},
        )
    }
}

/** The pill's compact face. */
private val LayerKind.pillGlyph: String
    get() = when (this) {
        LayerKind.GURMUKHI -> "ੳ"
        LayerKind.TRANSLITERATION -> "ਅ→A"
        LayerKind.ENGLISH -> "EN"
    }

/** The full name, for TalkBack and the on/off hint. */
private val LayerKind.pillTitle: String
    get() = when (this) {
        LayerKind.GURMUKHI -> "Gurmukhi"
        LayerKind.TRANSLITERATION -> "Transliteration"
        LayerKind.ENGLISH -> "Translation"
    }

// endregion

// region Display menu

/**
 * The cog: Edit (#154, kept out of the crowded top bar) and Duplicate (#159),
 * which Ardaas, the Gurmukhi layer, and text size. A null [onEdit] or
 * [onDuplicate] hides its item.
 */
@Composable
private fun DisplayMenu(
    library: ArdaasLibrary,
    selectedVariantId: String,
    toggles: LayerToggles,
    fontScale: Double,
    isGurmukhiPinned: Boolean,
    onVariantChange: (String) -> Unit,
    onGurmukhiChange: (Boolean) -> Unit,
    onAdjustFontScale: (Double) -> Unit,
    onEdit: (() -> Unit)?,
    onDuplicate: (() -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = "Display Options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onEdit != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_edit)) },
                    onClick = {
                        expanded = false
                        onEdit()
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                )
            }
            if (onDuplicate != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_duplicate)) },
                    onClick = {
                        expanded = false
                        onDuplicate()
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_duplicate), contentDescription = null) },
                )
            }
            if (onEdit != null || onDuplicate != null) HorizontalDivider()
            if (library.variants.size > 1) {
                MenuSectionHeader("Ardaas")
                for (variant in library.variants) {
                    val selected = variant.id == selectedVariantId
                    DropdownMenuItem(
                        text = { Text(variant.displayName) },
                        onClick = {
                            expanded = false
                            if (!selected) onVariantChange(variant.id)
                        },
                        leadingIcon = { RadioButton(selected = selected, onClick = null) },
                    )
                }
                HorizontalDivider()
            }
            MenuSectionHeader("Layers")
            // Toggles and size steps leave the menu open, so the effect can
            // be seen and repeated; picking a variant closes it.
            DropdownMenuItem(
                text = { Text("Gurmukhi") },
                onClick = { onGurmukhiChange(!toggles.gurmukhi) },
                enabled = !isGurmukhiPinned,
                leadingIcon = {
                    Checkbox(checked = toggles.gurmukhi, onCheckedChange = null, enabled = !isGurmukhiPinned)
                },
            )
            HorizontalDivider()
            MenuSectionHeader("Text Size")
            DropdownMenuItem(
                text = { Text("Larger Text") },
                onClick = { onAdjustFontScale(FontScale.STEP) },
                enabled = FontScale.canIncrease(fontScale),
            )
            DropdownMenuItem(
                text = { Text("Smaller Text") },
                onClick = { onAdjustFontScale(-FontScale.STEP) },
                enabled = FontScale.canDecrease(fontScale),
            )
        }
    }
}

@Composable
private fun MenuSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { heading() },
    )
}

// endregion

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ReaderScreenPreview() {
    // Placeholder segments, deliberately not scripture: the real text only
    // ever comes from the proof-read bundle, which previews cannot load.
    val segments = listOf("a", "b").map { id ->
        ArdaasSegment(id, "ਗੁਰਮੁਖੀ", "gurmukhī", "Preview segment $id.")
    }
    val content = ArdaasContent(1, listOf("preview"), segments, BentiSlot("a"))
    val library = ArdaasLibrary(listOf(ArdaasVariant(ArdaasLibrary.DEFAULT_VARIANT_ID, "SGPC", content)))
    val saved = SavedArdaas(
        label = "Morning Ardaas",
        bentiText = "Please bless the family with health and chardi kala.",
        bentiGurmukhi = "ਪਰਿਵਾਰ ਨੂੰ ਤੰਦਰੁਸਤੀ ਅਤੇ ਚੜ੍ਹਦੀ ਕਲਾ ਬਖ਼ਸ਼ੋ ਜੀ।",
        bentiTransliteration = "parivār nūṃ tandarustī ate charhdī kalā bakhsho jī.",
    )
    ArdaasTheme {
        ReaderScreen(
            savedArdaas = saved,
            library = library,
            occasionCatalog = null,
            onBack = {},
            onVariantChange = {},
        )
    }
}
