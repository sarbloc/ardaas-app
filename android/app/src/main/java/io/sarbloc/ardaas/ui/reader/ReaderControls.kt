package io.sarbloc.ardaas.ui.reader

import io.sarbloc.ardaas.composer.BentiLayers
import io.sarbloc.ardaas.composer.LayerAvailability
import io.sarbloc.ardaas.composer.LayerToggles
import io.sarbloc.ardaas.composer.hidesLayer
import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.LayerKind

/**
 * True when [kind]'s toggle is on but switching it off would achieve nothing,
 * so the control is disabled rather than left as a dead one — iOS's
 * `ReaderView.isPinnedLayer`, lifted out of the view so it can be tested.
 *
 * Two ways that happens:
 * - It is the only layer visible anywhere on screen (toggled on AND carried
 *   by the variant or the benti). Unchecking it would leave nothing the reader
 *   asked for; the segments' never-blank guard is a backstop, not a
 *   destination.
 * - Neither the segments nor the benti would drop it, because a never-blank
 *   rule puts it straight back: the segments' guard restores Gurmukhi on a
 *   variant that carries nothing else, and the benti's fallback restores the
 *   single written layer of a benti still awaiting translation.
 */
fun isPinnedLayer(kind: LayerKind, toggles: LayerToggles, content: ArdaasContent?, benti: BentiLayers): Boolean {
    if (!toggles[kind]) return false
    if (LayerAvailability.screen(content, benti).visibleLayerCount(toggles) == 1) return true
    return !LayerAvailability.canonical(content).hidesLayer(kind, toggles) && !benti.hidesLayer(kind, toggles)
}
