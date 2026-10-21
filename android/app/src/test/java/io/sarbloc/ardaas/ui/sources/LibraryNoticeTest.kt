package io.sarbloc.ardaas.ui.sources

import io.sarbloc.ardaas.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The library list (#139) as the build generates it: every packaged library
 * is there, with a licence whose full text actually made it in. The plugin's
 * strict mode already fails the build on a missing or unexpected licence;
 * this catches what it cannot — an empty text — and a plugin upgrade that
 * changes the JSON shape [LibraryNotice.parse] reads.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryNoticeTest {

    private val libraries: List<LibraryNotice> by lazy {
        val raw = RuntimeEnvironment.getApplication().resources.openRawResource(R.raw.aboutlibraries)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        LibraryNotice.parse(raw)
    }

    @Test
    fun everyLibraryCarriesTheFullTextOfALicence() {
        assertTrue("only ${libraries.size} libraries listed", libraries.size > 30)
        for (library in libraries) {
            assertFalse("${library.id} has no licence", library.licences.isEmpty())
            for (licence in library.licences) {
                assertTrue("${library.id}: ${licence.name} has no text", licence.text.length > 500)
            }
        }
    }

    @Test
    fun theAppsDirectDependenciesAreListed() {
        val ids = libraries.map { it.id }.toSet()
        for (id in listOf(
            "androidx.activity:activity-compose",
            "androidx.compose.material3:material3",
            "androidx.compose.ui:ui",
            "androidx.room:room-runtime",
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-serialization-json",
        )) {
            assertTrue("$id is not listed", id in ids)
        }
        assertTrue("BOMs ship no code", ids.none { it.endsWith("-bom") })
    }

    @Test
    fun onnxRuntimeIsCuratedRatherThanGenerated() {
        assertTrue(libraries.none { it.id.startsWith("com.microsoft.onnxruntime:") })
        val curated = OpenSourceLicence.all.single { it.assetName == "licence-onnxruntime.txt" }
        assertEquals("licence-onnxruntime-third-party-notices.txt", curated.thirdPartyNoticesAssetName)
    }

    @Test
    fun parseResolvesLicencesAndSortsByName() {
        val raw = """
            {"libraries": [
              {"uniqueId": "b:zeta", "artifactVersion": "2", "name": "zeta", "licenses": ["L"], "extra": 1},
              {"uniqueId": "a:alpha", "name": "", "licenses": ["L"]},
              {"uniqueId": "c:Beta", "artifactVersion": "1", "name": "Beta", "licenses": ["L"]}
            ],
            "licenses": {"L": {"name": "Some Licence", "content": "text", "url": "https://example.com"}}}
        """.trimIndent()
        val parsed = LibraryNotice.parse(raw)
        assertEquals(listOf("a:alpha", "Beta", "zeta"), parsed.map { it.name })
        assertEquals(null, parsed.first().version)
        assertEquals(listOf(LibraryNotice.Licence("Some Licence", "text")), parsed.last().licences)
    }

    @Test
    fun aLibraryNamingAnUnknownLicenceIsAnError() {
        val raw = """{"libraries": [{"uniqueId": "a:b", "licenses": ["missing"]}], "licenses": {}}"""
        val error = assertThrows(IllegalStateException::class.java) { LibraryNotice.parse(raw) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("missing"))
    }
}
