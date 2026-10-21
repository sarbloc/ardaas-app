package io.sarbloc.ardaas.ui.compose

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.sarbloc.ardaas.composer.BentiDraft
import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines

/**
 * Keeps a [BentiDraft] across configuration changes and process death, for
 * `rememberSaveable(stateSaver = BentiDraftSaver)`. One saveable value rather
 * than one per field, so [BentiDraft.applyTranslation] — which sets the
 * Gurmukhi and its source together — can never be restored half-applied.
 *
 * [BentiDraft.translatedFrom] is saved as well: without it a restored draft
 * would silently stop noticing that the English changed after translating,
 * and the stale-save alert would never fire. So is [BentiDraft.isDraftOpen],
 * so an emptied Gurmukhi editor is still on screen after a rotation (#131); a
 * list saved before it existed restores it from the Gurmukhi, as before.
 */
val BentiDraftSaver: Saver<BentiDraft, Any> = listSaver<BentiDraft, Any?>(
    save = { draft -> listOf(draft.typed, draft.gurmukhi, draft.translatedFrom, draft.isDraftOpen) },
    restore = { saved ->
        val gurmukhi = (saved.getOrNull(1) as? String).orEmpty()
        BentiDraft(
            typed = (saved.getOrNull(0) as? String).orEmpty(),
            gurmukhi = gurmukhi,
            translatedFrom = saved.getOrNull(2) as? String,
            isDraftOpen = saved.getOrNull(3) as? Boolean ?: gurmukhi.trimmingWhitespaceAndNewlines().isNotEmpty(),
        )
    },
)
