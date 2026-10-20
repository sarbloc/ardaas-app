package io.sarbloc.ardaas.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.ui.components.BackTopAppBar
import io.sarbloc.ardaas.ui.components.ContentUnavailable
import io.sarbloc.ardaas.ui.reader.ReaderScreen
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import io.sarbloc.ardaas.app.AppContainer
import io.sarbloc.ardaas.app.BundledContent
import io.sarbloc.ardaas.app.LoadState
import io.sarbloc.ardaas.app.map
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.translation.BentiTranslationService
import io.sarbloc.ardaas.ui.compose.ComposeScreen
import io.sarbloc.ardaas.ui.diagnostics.BuildDiagnostics
import io.sarbloc.ardaas.ui.diagnostics.DiagnosticsHook
import io.sarbloc.ardaas.ui.compose.OccasionDraftSaver
import io.sarbloc.ardaas.ui.compose.rememberComposeTranslationSession
import io.sarbloc.ardaas.ui.home.HomeScreen
import io.sarbloc.ardaas.ui.navigation.BackStack
import io.sarbloc.ardaas.persistence.occasionChoice
import io.sarbloc.ardaas.ui.navigation.Destination
import io.sarbloc.ardaas.ui.occasion.OccasionPickerDialog
import io.sarbloc.ardaas.ui.occasion.withPickedOccasion
import io.sarbloc.ardaas.ui.sources.SourcesScreen
import io.sarbloc.ardaas.ui.sources.appVersionString

/**
 * The app shell — iOS's `ContentView`: watches the app's data and hosts the
 * screens. The data is opened once, in [AppContainer]; this only observes it.
 */
@Composable
fun AppRoot(container: AppContainer) {
    val bundledContent by container.bundledContent.collectAsState()
    val savedArdaas by container.savedArdaas.collectAsState()
    val context = LocalContext.current
    val version = remember(context) { appVersionString(context) }

    AppNavHost(
        bundledContent = bundledContent,
        savedArdaas = savedArdaas,
        version = version,
        onDelete = container::delete,
        onUpdate = container::update,
        onInsert = container::insert,
        translationService = { container.translationService },
    )
}

/**
 * The screens and the [BackStack] between them, over one brand gradient.
 *
 * Each screen's saveable state (scroll position, say) is kept while it is on
 * the stack and dropped once it is popped, so returning to Home lands where
 * the reader left it, and reopening a record starts fresh.
 */
@Composable
fun AppNavHost(
    bundledContent: BundledContent,
    savedArdaas: LoadState<List<SavedArdaas>>,
    version: String,
    onDelete: suspend (SavedArdaas) -> Boolean,
    onUpdate: suspend (SavedArdaas) -> Boolean,
    onInsert: suspend (SavedArdaas) -> Boolean,
    translationService: () -> BentiTranslationService,
    diagnostics: DiagnosticsHook = BuildDiagnostics,
) {
    val backStack = rememberSaveable(saver = BackStack.Saver) { BackStack() }
    val screenState = rememberSaveableStateHolder()
    val goBack: () -> Unit = {
        backStack.pop()?.let { screenState.removeState(it.key) }
    }
    BackHandler(enabled = backStack.canGoBack, onBack = goBack)

    ArdaasBackground {
        AnimatedContent(
            targetState = backStack.current,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { destination ->
            screenState.SaveableStateProvider(destination.key) {
                when (destination) {
                    Destination.Home -> HomeScreen(
                        savedArdaas = savedArdaas,
                        onOpen = { backStack.push(Destination.Reader(it.id)) },
                        onCompose = { backStack.push(Destination.Compose) },
                        onShowSources = { backStack.push(Destination.Sources) },
                        onDelete = onDelete,
                        diagnosticsAction = {
                            diagnostics.HomeAction(onOpen = { backStack.push(Destination.Diagnostics) })
                        },
                    )
                    Destination.Diagnostics -> diagnostics.Screen(
                        translationService = translationService,
                        onBack = { if (backStack.current == destination) goBack() },
                    )
                    Destination.Sources -> SourcesScreen(
                        library = bundledContent.library,
                        version = version,
                        onBack = goBack,
                    )
                    Destination.Compose -> {
                        // Hoisted here, not inside the screen, so the occasion
                        // picker (#90) can write its choice back.
                        var occasion by rememberSaveable(stateSaver = OccasionDraftSaver) { mutableStateOf(OccasionDraft()) }
                        var editingOccasion by rememberSaveable { mutableStateOf(false) }
                        val occasionCatalog = bundledContent.occasionCatalog.valueOrNull
                        // Retained, not remembered: a configuration change
                        // keeps a download or translation running, and the
                        // session ends — cancelling both — only when this
                        // destination leaves composition for good.
                        val translation = rememberComposeTranslationSession(translationService)
                        ComposeScreen(
                            library = bundledContent.library.valueOrNull,
                            occasionCatalog = occasionCatalog,
                            occasion = occasion,
                            onOccasionChange = { occasion = it },
                            onEditOccasion = occasionCatalog?.let { { editingOccasion = true } },
                            translation = translation,
                            onSave = onInsert,
                            // As iOS dismisses the sheet: back to Home, where
                            // the new record leads the list. Only while this
                            // screen is still on top.
                            onClose = { if (backStack.current == destination) goBack() },
                        )
                        if (editingOccasion && occasionCatalog != null) {
                            // No ardaasContent: Compose shows its own live
                            // "Where it goes" preview, for the variant chosen
                            // on that screen, right under the row.
                            OccasionPickerDialog(
                                catalog = occasionCatalog,
                                choice = occasion.choice,
                                onDone = { picked ->
                                    editingOccasion = false
                                    occasion = OccasionDraft.from(picked, occasionCatalog)
                                },
                                onCancel = { editingOccasion = false },
                            )
                        }
                    }
                    is Destination.Reader -> ReaderDestination(
                        savedArdaas = savedArdaas.map { all -> all.firstOrNull { it.id == destination.savedArdaasId } },
                        bundledContent = bundledContent,
                        onUpdate = onUpdate,
                        // Leave only if this Reader is still the top screen —
                        // never pop whatever replaced it mid-transition.
                        onRecordGone = { if (backStack.current == destination) goBack() },
                        onBack = goBack,
                    )
                }
            }
        }
    }
}

/**
 * The Reader route. [savedArdaas] is the live record (null once it no longer
 * exists), so a write through [onUpdate] comes straight back as the new
 * record. Waits for both bundles: the occasion list loads alongside the
 * scripture, and rendering before it lands would flash the `…..` dots where
 * a chosen occasion belongs. A failed occasion list is no catalog at all —
 * the Reader carries on — but failed scripture is iOS's "Ardaas Text
 * Unavailable".
 */
@Composable
private fun ReaderDestination(
    savedArdaas: LoadState<SavedArdaas?>,
    bundledContent: BundledContent,
    onUpdate: suspend (SavedArdaas) -> Boolean,
    onRecordGone: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Saveable, so the picker is still open after the activity is recreated.
    var editingOccasion by rememberSaveable { mutableStateOf(false) }
    when (savedArdaas) {
        LoadState.Loading -> Unit
        is LoadState.Loaded -> {
            val record = savedArdaas.value
            if (record == null) {
                // A back stack restored after the record was deleted: there
                // is nothing to read, so go back to the list.
                LaunchedEffect(Unit) { onRecordGone() }
                return
            }
            val library = bundledContent.library
            val catalog = bundledContent.occasionCatalog
            when {
                library is LoadState.Failed -> ReaderTextUnavailable(record.label, library.error, onBack)
                library is LoadState.Loaded && catalog !is LoadState.Loading -> {
                    val occasionCatalog = catalog.valueOrNull
                    ReaderScreen(
                        savedArdaas = record,
                        library = library.value,
                        occasionCatalog = occasionCatalog,
                        onBack = onBack,
                        onVariantChange = { variantId ->
                            scope.launch { onUpdate(record.copy(variantId = variantId)) }
                        },
                        onEditOccasion = { editingOccasion = true },
                    )
                    if (editingOccasion && occasionCatalog != null) {
                        OccasionPickerDialog(
                            catalog = occasionCatalog,
                            choice = record.occasionChoice,
                            ardaasContent = library.value.resolvedVariant(record.variantId).content,
                            // Persisted on Done, like the variant picker
                            // writes straight to the record; Cancel keeps it.
                            onDone = { picked ->
                                editingOccasion = false
                                record.withPickedOccasion(picked, occasionCatalog)?.let { updated ->
                                    scope.launch { onUpdate(updated) }
                                }
                            },
                            onCancel = { editingOccasion = false },
                        )
                    }
                }
                else -> Unit
            }
        }
        // The list could not load; Home shows why.
        is LoadState.Failed -> LaunchedEffect(Unit) { onRecordGone() }
    }
}

/** iOS's `ContentUnavailableView("Ardaas Text Unavailable", …)`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTextUnavailable(title: String, error: Throwable, onBack: () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { BackTopAppBar(title, onBack) },
    ) { innerPadding ->
        ContentUnavailable(
            icon = painterResource(R.drawable.ic_warning),
            title = stringResource(R.string.reader_text_unavailable),
            description = error.message ?: error.toString(),
            modifier = Modifier.padding(innerPadding),
        )
    }
}
