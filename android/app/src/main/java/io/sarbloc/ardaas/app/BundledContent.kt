package io.sarbloc.ardaas.app

import io.sarbloc.ardaas.content.ArdaasLibrary
import io.sarbloc.ardaas.occasion.OccasionCatalog
import java.io.InputStream

/**
 * Everything the app reads from its bundled assets, loaded once per process.
 *
 * The two are loaded — and fail — independently, as on iOS: the scripture
 * ([library]) is what the Reader and About & Sources exist to show, so its
 * failure is drawn on those screens. The occasion list is an enhancement; the
 * Reader carries on without it (a free-text occasion still renders, see
 * `SavedArdaas.occasionLayers`), so a broken [occasionCatalog] must never
 * cost the user the scripture.
 */
data class BundledContent(
    val library: LoadState<ArdaasLibrary>,
    val occasionCatalog: LoadState<OccasionCatalog>,
) {
    companion object {
        /** Before the first load has finished. */
        val Loading = BundledContent(LoadState.Loading, LoadState.Loading)

        /**
         * Loads both bundles, each captured as its own [LoadState]. Blocking
         * file I/O — call it off the main thread.
         *
         * @param open opens a bundled file by name, or returns null when absent.
         */
        fun load(open: (fileName: String) -> InputStream?): BundledContent = BundledContent(
            library = loadCatching { ArdaasLibrary.load(open) },
            occasionCatalog = loadCatching { OccasionCatalog.load(open) },
        )
    }
}
