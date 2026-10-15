package io.sarbloc.ardaas.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import io.sarbloc.ardaas.app.AppContainer
import io.sarbloc.ardaas.app.BundledContent
import io.sarbloc.ardaas.app.LoadState
import io.sarbloc.ardaas.app.map
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.ui.home.HomeScreen
import io.sarbloc.ardaas.ui.navigation.BackStack
import io.sarbloc.ardaas.ui.navigation.Destination
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
                    )
                    Destination.Sources -> SourcesScreen(
                        library = bundledContent.library,
                        version = version,
                        onBack = goBack,
                    )
                    Destination.Compose -> ComposePlaceholderScreen(onBack = goBack)
                    is Destination.Reader -> ReaderDestination(
                        savedArdaas = savedArdaas.map { all -> all.firstOrNull { it.id == destination.savedArdaasId } },
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
 * exists); the bundled library and occasion list are in [AppNavHost]'s
 * `bundledContent`, for the real Reader to take alongside it.
 */
@Composable
private fun ReaderDestination(
    savedArdaas: LoadState<SavedArdaas?>,
    onRecordGone: () -> Unit,
    onBack: () -> Unit,
) {
    when (savedArdaas) {
        LoadState.Loading -> Unit
        is LoadState.Loaded -> {
            val record = savedArdaas.value
            if (record != null) {
                ReaderPlaceholderScreen(savedArdaas = record, onBack = onBack)
            } else {
                // A back stack restored after the record was deleted: there
                // is nothing to read, so go back to the list.
                LaunchedEffect(Unit) { onRecordGone() }
            }
        }
        // The list could not load; Home shows why.
        is LoadState.Failed -> LaunchedEffect(Unit) { onRecordGone() }
    }
}
