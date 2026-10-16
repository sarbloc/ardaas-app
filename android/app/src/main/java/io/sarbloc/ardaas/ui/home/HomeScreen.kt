package io.sarbloc.ardaas.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.app.LoadState
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.ui.ArdaasBackground
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.ArdaasTheme
import io.sarbloc.ardaas.ui.components.CardShape
import io.sarbloc.ardaas.ui.components.ContentUnavailable
import io.sarbloc.ardaas.ui.components.ardaasTopAppBarColors
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Home: the saved Ardaas, newest first. Port of `HomeView.swift` — tap a row
 * to read it, swipe it away to delete it, "+" (or the empty state's button)
 * to compose a new one, and the info button for About & Sources.
 *
 * [onDelete] returns false when the delete failed, and the row slides back.
 * [diagnosticsAction] is the debug-only Benti Lab entry (iOS: "On-device
 * translation diagnostics (#42) — debug builds only"); release supplies
 * nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    savedArdaas: LoadState<List<SavedArdaas>>,
    onOpen: (SavedArdaas) -> Unit,
    onCompose: () -> Unit,
    onShowSources: () -> Unit,
    onDelete: suspend (SavedArdaas) -> Boolean,
    diagnosticsAction: @Composable () -> Unit = {},
) {
    // iOS's large navigation title, collapsing as the list scrolls.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                navigationIcon = {
                    IconButton(onClick = onShowSources) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.about_and_sources))
                    }
                },
                actions = {
                    diagnosticsAction()
                    IconButton(onClick = onCompose) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.compose_ardaas))
                    }
                },
                colors = ardaasTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        when (savedArdaas) {
            // Blank rather than the empty state: "No saved Ardaas yet" for a
            // moment before the list arrives would be a lie.
            LoadState.Loading -> Unit
            is LoadState.Failed -> ContentUnavailable(
                icon = painterResource(R.drawable.ic_warning),
                title = stringResource(R.string.home_load_failed_title),
                description = savedArdaas.error.message ?: savedArdaas.error.toString(),
                modifier = Modifier.padding(innerPadding),
            )
            is LoadState.Loaded ->
                if (savedArdaas.value.isEmpty()) {
                    EmptyState(onCompose = onCompose, modifier = Modifier.padding(innerPadding))
                } else {
                    SavedArdaasList(savedArdaas.value, innerPadding, onOpen, onDelete)
                }
        }
    }
}

@Composable
private fun EmptyState(onCompose: () -> Unit, modifier: Modifier = Modifier) {
    ContentUnavailable(
        icon = painterResource(R.drawable.ic_book),
        title = stringResource(R.string.home_empty_title),
        description = stringResource(R.string.home_empty_description),
        modifier = modifier,
    ) {
        Button(onClick = onCompose) {
            Text(stringResource(R.string.compose_ardaas))
        }
    }
}

@Composable
private fun SavedArdaasList(
    savedArdaas: List<SavedArdaas>,
    innerPadding: PaddingValues,
    onOpen: (SavedArdaas) -> Unit,
    onDelete: suspend (SavedArdaas) -> Boolean,
) {
    val layoutDirection = LocalLayoutDirection.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // The Scaffold's padding goes inside the list, so rows scroll under
        // the bars and the gradient stays edge to edge.
        contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
            end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
            top = innerPadding.calculateTopPadding() + 8.dp,
            bottom = innerPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(savedArdaas, key = { it.id }) { saved ->
            SavedArdaasRow(
                saved = saved,
                date = formatSavedDate(saved.createdAt, zone, locale),
                onOpen = { onOpen(saved) },
                onDelete = { onDelete(saved) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedArdaasRow(
    saved: SavedArdaas,
    date: String,
    onOpen: () -> Unit,
    onDelete: suspend () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val delete: () -> Unit = {
        scope.launch {
            if (!onDelete()) dismissState.reset()
        }
    }
    val deleteLabel = stringResource(R.string.home_delete)

    // Trailing swipe only, deleting without a confirmation — exactly iOS's
    // `.onDelete` on a List.
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.clip(CardShape),
        enableDismissFromStartToEnd = false,
        onDismiss = { delete() },
        backgroundContent = { DeleteBackground(dismissState) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ArdaasColors.RaisedFill)
                .clickable(onClickLabel = stringResource(R.string.home_open), onClick = onOpen)
                // A swipe is no use to a screen-reader user; iOS's onDelete
                // offers its own accessibility action, and so does this row.
                .semantics {
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { delete(); true })
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(saved.label, style = MaterialTheme.typography.titleMedium, color = ArdaasColors.Parchment)
            Text(date, style = MaterialTheme.typography.bodyMedium, color = ArdaasColors.Mist)
        }
    }
}

/**
 * The delete colour behind a swiped row, drawn only in the strip the row has
 * uncovered: the row's fill is translucent (so the gradient shows through),
 * and an unclipped red layer would tint every row at rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteBackground(state: SwipeToDismissBoxState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                val revealed = abs(state.requireOffset()).coerceAtMost(size.width)
                val clipLeft = if (layoutDirection == LayoutDirection.Ltr) size.width - revealed else 0f
                val clipRight = if (layoutDirection == LayoutDirection.Ltr) size.width else revealed
                clipRect(left = clipLeft, right = clipRight) { this@drawWithContent.drawContent() }
            }
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            painterResource(R.drawable.ic_delete),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onError,
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    ArdaasTheme {
        ArdaasBackground {
            HomeScreen(
                savedArdaas = LoadState.Loaded(
                    listOf(
                        SavedArdaas(label = "Morning Ardaas", bentiText = "A personal benti.", createdAt = Instant.now(), id = 2),
                        SavedArdaas(label = "For the family", bentiText = "Another benti.", createdAt = Instant.now(), id = 1),
                    ),
                ),
                onOpen = {},
                onCompose = {},
                onShowSources = {},
                onDelete = { true },
            )
        }
    }
}

@Preview
@Composable
private fun HomeScreenEmptyPreview() {
    ArdaasTheme {
        ArdaasBackground {
            HomeScreen(LoadState.Loaded(emptyList()), onOpen = {}, onCompose = {}, onShowSources = {}, onDelete = { true })
        }
    }
}
