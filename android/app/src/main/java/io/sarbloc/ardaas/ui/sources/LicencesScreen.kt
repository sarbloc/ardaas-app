package io.sarbloc.ardaas.ui.sources

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.app.LoadState
import io.sarbloc.ardaas.app.loadCatching
import io.sarbloc.ardaas.ui.ArdaasColors
import io.sarbloc.ardaas.ui.components.BackTopAppBar
import io.sarbloc.ardaas.ui.components.GroupedSection
import io.sarbloc.ardaas.ui.components.SectionDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "Ardaas"

/** Space between one card and the next. */
private val BlockGap = 24.dp

/** A hand-curated notice ([OpenSourceLicence]) as loaded from the assets. */
private data class CuratedNotice(
    val licence: OpenSourceLicence,
    /** The licence, reflowed. */
    val text: LoadState<String>,
    /** Its third-party notices, one reflowed text per component; null if it has none. */
    val thirdParty: LoadState<List<String>>?,
)

private data class LicencesContent(
    val curated: List<CuratedNotice>,
    /** Every packaged library, licence texts already reflowed. */
    val libraries: LoadState<List<LibraryNotice>>,
)

/** Which rows are expanded, by key; saved so rotation keeps them open. */
private val expandedSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * Open-source licences (#132, #139): the full notice of every third-party
 * component the app ships. First the hand-curated ones in
 * [OpenSourceLicence.all] (the translation stack, with ONNX Runtime's own
 * third-party notices behind a toggle), then every Android library the build
 * packages, each collapsed to its name, version and licence until tapped.
 * Pushed from About & Sources. Everything is a lazy row, so the ~70 libraries
 * and ONNX Runtime's ~300 KB of notices cost nothing until scrolled to. A
 * text that fails to load says so rather than leaving a gap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val content by produceState<LicencesContent?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { loadContent(context) }
    }
    var expanded by rememberSaveable(stateSaver = expandedSaver) { mutableStateOf(emptySet<String>()) }
    val toggle = { key: String -> expanded = if (key in expanded) expanded - key else expanded + key }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = { BackTopAppBar(stringResource(R.string.licences_title), onBack, scrollBehavior) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            val loaded = content ?: return@LazyColumn
            for (notice in loaded.curated) curatedNotice(notice, expanded, toggle)
            libraries(loaded.libraries, expanded, toggle)
        }
    }
}

private fun loadContent(context: Context): LicencesContent {
    val assets = context.assets
    val curated = OpenSourceLicence.all.map { licence ->
        CuratedNotice(
            licence = licence,
            text = loadLogged(licence.assetName) { OpenSourceLicence.reflow(licence.loadText(assets::open)) },
            thirdParty = licence.thirdPartyNoticesAssetName?.let { name ->
                loadLogged(name) {
                    OpenSourceLicence.noticeSections(OpenSourceLicence.loadAsset(name, assets::open))
                        .map(OpenSourceLicence::reflow)
                }
            },
        )
    }
    val libraries = loadLogged("res/raw/aboutlibraries.json") {
        val raw = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader(Charsets.UTF_8).use { it.readText() }
        // Nearly every library shares one licence text: reflow each once.
        val reflowed = HashMap<String, String>()
        LibraryNotice.parse(raw).map { library ->
            library.copy(
                licences = library.licences.map { it.copy(text = reflowed.getOrPut(it.text) { OpenSourceLicence.reflow(it.text) }) },
            )
        }
    }
    return LicencesContent(curated, libraries)
}

/** [block]'s result, or its failure logged with [what] failed to load. */
private inline fun <T> loadLogged(what: String, block: () -> T): LoadState<T> =
    loadCatching(block).also { if (it is LoadState.Failed) Log.e(TAG, "Licence text $what failed to load", it.error) }

private fun LazyListScope.curatedNotice(notice: CuratedNotice, expanded: Set<String>, toggle: (String) -> Unit) {
    val licence = notice.licence
    val thirdParty = notice.thirdParty
    val sections = (thirdParty as? LoadState.Loaded)?.value
    val sectionsKey = "${licence.assetName}:third-party"
    val showSections = sections != null && sectionsKey in expanded
    item(key = licence.assetName) {
        GroupedSection(licence.component, Modifier.padding(bottom = if (showSections) 8.dp else BlockGap)) {
            LicenceRow(licence.source, ArdaasColors.Parchment)
            SectionDivider()
            LoadedText(notice.text)
            when (thirdParty) {
                null, LoadState.Loading -> Unit
                is LoadState.Failed -> {
                    SectionDivider()
                    LoadedText(thirdParty)
                }
                is LoadState.Loaded -> {
                    SectionDivider()
                    ExpandRow(
                        title = stringResource(R.string.licences_third_party_toggle, licence.component),
                        detail = pluralStringResource(R.plurals.licences_third_party_count, thirdParty.value.size, thirdParty.value.size),
                        expanded = showSections,
                        onToggle = { toggle(sectionsKey) },
                    )
                }
            }
        }
    }
    if (showSections) {
        itemsIndexed(sections, key = { index, _ -> "$sectionsKey:$index" }) { index, section ->
            CardRow(first = index == 0, last = index == sections.lastIndex) {
                SelectionContainer { LicenceRow(section, ArdaasColors.Mist) }
            }
        }
    }
}

private fun LazyListScope.libraries(libraries: LoadState<List<LibraryNotice>>, expanded: Set<String>, toggle: (String) -> Unit) {
    item(key = "libraries-header") {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) {
            Text(
                stringResource(R.string.licences_libraries_header),
                style = MaterialTheme.typography.titleSmall,
                color = ArdaasColors.Mist,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.licences_libraries_intro),
                style = MaterialTheme.typography.bodySmall,
                color = ArdaasColors.Mist,
            )
        }
    }
    when (libraries) {
        LoadState.Loading -> Unit
        is LoadState.Failed -> item(key = "libraries-failed") {
            CardRow(first = true, last = true) { LoadedText(libraries) }
        }
        is LoadState.Loaded -> itemsIndexed(libraries.value, key = { _, library -> "library:${library.id}" }) { index, library ->
            val key = "library:${library.id}"
            CardRow(first = index == 0, last = index == libraries.value.lastIndex) {
                LibraryRow(library, expanded = key in expanded, onToggle = { toggle(key) })
            }
        }
    }
}

@Composable
private fun LibraryRow(library: LibraryNotice, expanded: Boolean, onToggle: () -> Unit) {
    val licenceNames = library.licences.joinToString(", ") { it.name }
    ExpandRow(
        title = library.name,
        detail = library.version?.let { stringResource(R.string.licences_library_detail, it, licenceNames) } ?: licenceNames,
        expanded = expanded,
        onToggle = onToggle,
    )
    if (expanded) {
        for (licence in library.licences) {
            SectionDivider()
            SelectionContainer { LicenceRow(licence.text, ArdaasColors.Mist) }
        }
    }
}

/** A tappable title and detail line with a chevron that turns down while [expanded]. */
@Composable
private fun ExpandRow(title: String, detail: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(if (expanded) R.string.licences_collapse else R.string.licences_expand),
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = ArdaasColors.Parchment)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = ArdaasColors.Mist)
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = ArdaasColors.Mist,
            modifier = Modifier.rotate(if (expanded) 90f else 0f),
        )
    }
}

/**
 * One row of a card that spans several lazy items — the way a
 * [GroupedSection] looks, but each row composed only when scrolled to.
 * [first] and [last] round the card's corners; rows after the first get a
 * divider, and the last leaves the gap before the next card.
 */
@Composable
private fun CardRow(first: Boolean, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val top: Dp = if (first) 12.dp else 0.dp
    val bottom: Dp = if (last) 12.dp else 0.dp
    Column(
        Modifier
            .padding(bottom = if (last) BlockGap else 0.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(ArdaasColors.RaisedFill),
    ) {
        if (!first) SectionDivider()
        content()
    }
}

@Composable
private fun LoadedText(text: LoadState<String>) {
    when (text) {
        is LoadState.Loaded -> SelectionContainer { LicenceRow(text.value, ArdaasColors.Mist) }
        is LoadState.Failed -> LicenceRow(
            stringResource(R.string.licences_load_failed, text.error.message ?: text.error.toString()),
            ArdaasColors.Mist,
        )
        LoadState.Loading -> Unit
    }
}

@Composable
private fun LicenceRow(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
