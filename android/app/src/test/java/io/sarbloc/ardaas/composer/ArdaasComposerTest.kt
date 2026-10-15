package io.sarbloc.ardaas.composer

import io.sarbloc.ardaas.content.ArdaasSegment
import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.content.fixtureContent
import io.sarbloc.ardaas.content.fixtureSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of ArdaasTests/ArdaasComposerTests.swift, case for case. */
class ArdaasComposerTest {
    private val content = fixtureContent(
        listOf(fixtureSegment("a"), fixtureSegment("b"), fixtureSegment("c")),
        slotAfter = "b",
    )

    private val benti = BentiLayers(
        gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ",
        transliteration = "merī bentī",
        english = "my benti",
    )

    private fun canonicalOnly(segments: List<ArdaasSegment>) =
        segments.map { RenderItem.Canonical(it) }

    @Test
    fun bentiInsertsImmediatelyAfterSlotSegment() {
        val items = ArdaasComposer.compose(content, benti)
        assertEquals(
            listOf(
                RenderItem.Canonical(fixtureSegment("a")),
                RenderItem.Canonical(fixtureSegment("b")),
                RenderItem.Benti(benti),
                RenderItem.Canonical(fixtureSegment("c")),
            ),
            items,
        )
    }

    @Test
    fun allThreeLayersSurviveComposition() {
        val layers = (ArdaasComposer.compose(content, benti)[2] as? RenderItem.Benti)?.layers
            ?: throw AssertionError("expected the benti at the slot position")
        assertEquals("ਮੇਰੀ ਬੇਨਤੀ", layers.gurmukhi)
        assertEquals("merī bentī", layers.transliteration)
        assertEquals("my benti", layers.english)
    }

    /**
     * Every layer is trimmed independently, so padding on one layer never
     * leaks into the rendered text or into equality comparisons.
     */
    @Test
    fun eachLayerIsTrimmedIndependently() {
        val padded = BentiLayers(
            gurmukhi = "  ਮੇਰੀ ਬੇਨਤੀ\n",
            transliteration = "\tmerī bentī  ",
            english = "\n my benti \n",
        )
        val items = ArdaasComposer.compose(content, padded)
        assertEquals(RenderItem.Benti(benti), items[2])
    }

    /**
     * The layer the user actually typed is enough: an untranslated benti
     * still composes, so nothing is hidden while translation is pending.
     */
    @Test
    fun englishOnlyBentiStillComposes() {
        val items = ArdaasComposer.compose(content, BentiLayers(english = "my benti"))
        assertEquals(RenderItem.Benti(BentiLayers(english = "my benti")), items[2])
        assertEquals(content.segments.size + 1, items.size)
    }

    /**
     * Gurmukhi-only is the mirror case (a benti typed straight in Gurmukhi,
     * or a variant that renders only that layer).
     */
    @Test
    fun gurmukhiOnlyBentiStillComposes() {
        val items = ArdaasComposer.compose(content, BentiLayers(gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ"))
        assertEquals(RenderItem.Benti(BentiLayers(gurmukhi = "ਮੇਰੀ ਬੇਨਤੀ")), items[2])
    }

    @Test
    fun nilBentiYieldsCanonicalOnly() {
        val items = ArdaasComposer.compose(content, null)
        assertEquals(canonicalOnly(content.segments), items)
    }

    /** The empty rule: a benti counts as absent only when *every* layer is blank after trimming. */
    @Test
    fun allBlankBentiYieldsCanonicalOnly() {
        val blank = BentiLayers(gurmukhi = "", transliteration = "", english = "")
        assertTrue(blank.isEmpty)
        assertEquals(canonicalOnly(content.segments), ArdaasComposer.compose(content, blank))
    }

    @Test
    fun whitespaceOnlyLayersYieldCanonicalOnly() {
        val blank = BentiLayers(gurmukhi = " ", transliteration = "\t", english = " \n\t ")
        assertTrue(blank.isEmpty)
        assertEquals(canonicalOnly(content.segments), ArdaasComposer.compose(content, blank))
    }

    @Test
    fun unknownSlotYieldsCanonicalOnly() {
        val broken = fixtureContent(listOf(fixtureSegment("a")), slotAfter = "missing")
        val items = ArdaasComposer.compose(broken, benti)
        assertEquals(listOf(RenderItem.Canonical(fixtureSegment("a"))), items)
    }

    @Test
    fun slotOnLastSegmentAppendsBenti() {
        val tail = fixtureContent(listOf(fixtureSegment("a"), fixtureSegment("b")), slotAfter = "b")
        val items = ArdaasComposer.compose(tail, benti)
        assertEquals(RenderItem.Benti(benti), items.last())
    }

    @Test
    fun bundledContentComposesAtCanonicalPoint() {
        assertBundledSlot(
            variantId = "sgpc",
            expectedBefore = "nimaniyan-de-maan",
            expectedAfter = "bhul-chuk-maaf",
        )
    }

    /**
     * The benti's layers are stored and composed identically on either
     * variant — the variant decides what is *shown*, never what is *kept*.
     */
    @Test
    fun bundledBuddhaDalComposesAtCanonicalPoint() {
        assertBundledSlot(
            variantId = "buddha-dal",
            expectedBefore = "he-deen-dayal",
            expectedAfter = "sikh-parhde-sunde",
        )
    }

    private fun assertBundledSlot(variantId: String, expectedBefore: String, expectedAfter: String) {
        val bundled = requireNotNull(BundledAssets.library().variant(variantId)) { "no variant $variantId" }.content
        val items = ArdaasComposer.compose(bundled, benti)
        val bentiIndex = items.indexOf(RenderItem.Benti(benti))
        assertTrue("benti missing from $variantId", bentiIndex >= 0)
        val before = items.getOrNull(bentiIndex - 1) as? RenderItem.Canonical
        val after = items.getOrNull(bentiIndex + 1) as? RenderItem.Canonical
        if (before == null || after == null) throw AssertionError("benti must sit between canonical segments")
        assertEquals(expectedBefore, before.segment.id)
        assertEquals(expectedAfter, after.segment.id)
        assertEquals(bundled.segments.size + 1, items.size)
    }
}
