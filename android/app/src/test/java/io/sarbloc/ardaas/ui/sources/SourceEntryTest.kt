package io.sarbloc.ardaas.ui.sources

import io.sarbloc.ardaas.content.BundledAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEntryTest {

    @Test
    fun aWebAddressIsALinkLabelledWithItsHost() {
        val url = "https://www.discoversikhism.com/sikhism/sikh_ardas.html"
        assertEquals(SourceEntry.Link(url, "www.discoversikhism.com"), SourceEntry.of(url))
        assertEquals(SourceEntry.Link("http://example.org", "example.org"), SourceEntry.of("http://example.org"))
    }

    @Test
    fun proseIsShownAsWritten() {
        val prose = "Nihung Santhiya app — transcribed by hand"
        assertEquals(SourceEntry.Prose(prose), SourceEntry.of(prose))
    }

    @Test
    fun proseThatBeginsWithAnAddressStaysProse() {
        // Truncating this to "api.banidb.com" would lose the description and
        // link to an address that does not exist.
        val source = "https://api.banidb.com/v2/angs/268 and /383 — BaniDB"
        assertEquals(SourceEntry.Prose(source), SourceEntry.of(source))
    }

    @Test
    fun otherSchemesAndHostlessStringsAreProse() {
        for (source in listOf("mailto:someone@example.org", "ftp://example.org/x", "https:relative", "ardaas-sgpc.json")) {
            assertTrue(source, SourceEntry.of(source) is SourceEntry.Prose)
        }
    }

    /** The screen renders the bundle itself: every variant cites something, and nothing renders blank. */
    @Test
    fun everyBundledSourceRendersAsSomething() {
        val variants = BundledAssets.library().variants
        assertFalse(variants.isEmpty())
        for (variant in variants) {
            assertFalse("${variant.id} cites no sources", variant.content.sources.isEmpty())
            for (entry in variant.content.sources.map(SourceEntry::of)) {
                val shown = when (entry) {
                    is SourceEntry.Link -> entry.host
                    is SourceEntry.Prose -> entry.text
                }
                assertTrue("${variant.id} has a blank source", shown.isNotBlank())
            }
        }
    }

    @Test
    fun theStandardTextsSourcesAreLinks() {
        val sgpc = BundledAssets.content("sgpc").sources.map(SourceEntry::of)
        assertTrue(sgpc.all { it is SourceEntry.Link })
    }
}
