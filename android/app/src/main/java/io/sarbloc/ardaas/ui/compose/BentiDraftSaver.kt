package io.sarbloc.ardaas.ui.compose

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.sarbloc.ardaas.composer.BentiDraft

/**
 * Keeps a [BentiDraft] across configuration changes and process death, for
 * `rememberSaveable(stateSaver = BentiDraftSaver)`. One saveable value rather
 * than one per field, so [BentiDraft.applyTranslation] — which sets the
 * Gurmukhi and its source together — can never be restored half-applied.
 *
 * [BentiDraft.translatedFrom] is saved as well: without it a restored draft
 * would silently stop noticing that the English changed after translating,
 * and the stale-save alert would never fire.
 */
val BentiDraftSaver: Saver<BentiDraft, Any> = listSaver<BentiDraft, String?>(
    save = { draft -> listOf(draft.typed, draft.gurmukhi, draft.translatedFrom) },
    restore = { saved ->
        BentiDraft(
            typed = saved.getOrNull(0).orEmpty(),
            gurmukhi = saved.getOrNull(1).orEmpty(),
            translatedFrom = saved.getOrNull(2),
        )
    },
)
