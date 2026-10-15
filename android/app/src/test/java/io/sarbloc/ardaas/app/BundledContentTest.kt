package io.sarbloc.ardaas.app

import io.sarbloc.ardaas.content.ArdaasLibraryException
import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.occasion.OccasionCatalogException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-level load of the bundle: the real assets load, and the scripture
 * and the occasion list fail independently — a broken occasion list must
 * never cost the reader the Ardaas, nor the reverse.
 */
class BundledContentTest {

    @Test
    fun theShippedBundleLoadsBoth() {
        val content = BundledContent.load(BundledAssets::open)
        assertEquals(LoadState.Loaded(BundledAssets.library()), content.library)
        assertEquals(LoadState.Loaded(BundledAssets.occasionCatalog()), content.occasionCatalog)
    }

    @Test
    fun aMissingOccasionListLeavesTheScriptureLoaded() {
        val content = BundledContent.load { name ->
            if (name == OccasionCatalog.FILE_NAME) null else BundledAssets.open(name)
        }
        assertTrue(content.library is LoadState.Loaded)
        assertEquals(LoadState.Failed(OccasionCatalogException.ResourceMissing), content.occasionCatalog)
    }

    @Test
    fun aMissingManifestFailsTheScriptureButNotTheOccasionList() {
        val content = BundledContent.load { name ->
            if (name == "variants.json") null else BundledAssets.open(name)
        }
        assertEquals(LoadState.Failed(ArdaasLibraryException.ManifestMissing), content.library)
        assertTrue(content.occasionCatalog is LoadState.Loaded)
    }

    @Test
    fun malformedJsonIsAFailureNotACrash() {
        val content = BundledContent.load { "{ not json".byteInputStream() }
        assertTrue(content.library is LoadState.Failed)
        assertTrue(content.occasionCatalog is LoadState.Failed)
    }
}
