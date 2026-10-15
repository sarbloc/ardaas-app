package io.sarbloc.ardaas.ui.compose

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.sarbloc.ardaas.occasion.OccasionDraft
import io.sarbloc.ardaas.occasion.OccasionSelection

/**
 * Keeps an [OccasionDraft] across configuration changes and process death,
 * for `rememberSaveable(stateSaver = OccasionDraftSaver)`. Both fields are
 * restored verbatim through the primary constructor — including "Other…"
 * with nothing typed yet, which [OccasionDraft.choice] would collapse.
 */
val OccasionDraftSaver: Saver<OccasionDraft, Any> = listSaver(
    save = { draft ->
        val (kind, id) = when (val selection = draft.selection) {
            OccasionSelection.Unset -> KIND_UNSET to ""
            is OccasionSelection.Catalog -> KIND_CATALOG to selection.id
            OccasionSelection.Custom -> KIND_CUSTOM to ""
        }
        listOf(kind, id, draft.customText)
    },
    restore = { (kind, id, customText) ->
        val selection = when (kind) {
            KIND_CATALOG -> OccasionSelection.Catalog(id)
            KIND_CUSTOM -> OccasionSelection.Custom
            else -> OccasionSelection.Unset
        }
        OccasionDraft(selection = selection, customText = customText)
    },
)

private const val KIND_UNSET = "unset"
private const val KIND_CATALOG = "catalog"
private const val KIND_CUSTOM = "custom"
