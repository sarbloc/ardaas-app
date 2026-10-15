package io.sarbloc.ardaas.ui.occasion

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.sarbloc.ardaas.occasion.Occasion
import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.occasion.OccasionCategory
import io.sarbloc.ardaas.occasion.OccasionChoice
import io.sarbloc.ardaas.occasion.OccasionCopy
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.occasion.OccasionSelection
import io.sarbloc.ardaas.persistence.SavedArdaas
import io.sarbloc.ardaas.persistence.occasionChoice
import io.sarbloc.ardaas.persistence.withOccasionChoice

// The occasion picker's decisions, kept out of the composable so they can be
// unit-tested: which groups it lists, how its state survives recreation, and
// what the Reader writes when it closes.

/** One headed group of catalog rows in the picker. */
data class OccasionPickerGroup(
    val category: OccasionCategory,
    val title: String,
    /** In bundled order, which is display order. */
    val entries: List<Occasion>,
)

/**
 * The catalog rows the picker lists between "None" and "Other…": one group
 * per [OccasionCategory], in the enum's order (paath, then occasion — iOS's
 * order), each holding that category's entries in catalog order. Built from
 * the catalog's own categories, so a re-categorised or added entry moves with
 * the data. A category with no entries gets no empty header.
 */
fun OccasionCatalog.pickerGroups(): List<OccasionPickerGroup> =
    OccasionCategory.entries.mapNotNull { category ->
        occasionsIn(category)
            .takeIf { it.isNotEmpty() }
            ?.let { OccasionPickerGroup(category, category.pickerTitle, it) }
    }

/** The header the picker shows over a category's entries. */
val OccasionCategory.pickerTitle: String
    get() = when (this) {
        OccasionCategory.PAATH -> OccasionCopy.PAATH_HEADER
        OccasionCategory.OCCASION -> OccasionCopy.OCCASIONS_HEADER
    }

/**
 * Bundle form of an [OccasionDraft], so the picker's in-progress state —
 * including "Other…" with words half typed, which no [OccasionChoice] can
 * hold — survives activity recreation and process death.
 */
internal object OccasionDraftBundle {
    private const val UNSET = "unset"
    private const val CATALOG = "catalog"
    private const val CUSTOM = "custom"

    fun encode(draft: OccasionDraft): List<String> = when (val selection = draft.selection) {
        OccasionSelection.Unset -> listOf(UNSET, "", draft.customText)
        is OccasionSelection.Catalog -> listOf(CATALOG, selection.id, draft.customText)
        OccasionSelection.Custom -> listOf(CUSTOM, "", draft.customText)
    }

    /** Null for anything [encode] did not write, so a malformed bundle falls back to a fresh draft. */
    fun decode(saved: List<String>): OccasionDraft? {
        if (saved.size != 3) return null
        val (kind, id, customText) = saved
        val selection = when (kind) {
            UNSET -> OccasionSelection.Unset
            CATALOG -> OccasionSelection.Catalog(id)
            CUSTOM -> OccasionSelection.Custom
            else -> return null
        }
        // The constructor, not withSelection: restore exactly what was saved.
        return OccasionDraft(selection = selection, customText = customText)
    }

    val saver: Saver<OccasionDraft, Any> = listSaver(save = { encode(it) }, restore = { decode(it) })
}

/**
 * The record the Reader writes when the picker's Done returns [picked], or
 * null when there is nothing to write.
 *
 * Compared with the choice the picker *opened on*, not the raw stored one:
 * iOS persists only on a change to its draft, so opening the picker and
 * tapping Done writes nothing. That matters for a record pointing at an entry
 * a later bundle retired — the picker opens it as "None" (see
 * [OccasionDraft.from]), and an untouched Done must not quietly rewrite the
 * stored id.
 */
fun SavedArdaas.withPickedOccasion(picked: OccasionChoice, catalog: OccasionCatalog): SavedArdaas? {
    val openedOn = OccasionDraft.from(occasionChoice, catalog).choice
    return if (picked == openedOn) null else withOccasionChoice(picked)
}
