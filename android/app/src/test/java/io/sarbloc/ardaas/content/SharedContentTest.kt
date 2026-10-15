package io.sarbloc.ardaas.content

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scripture Android packages must be byte-identical to what iOS ships.
 *
 * Compares the iOS source files against the MERGED assets directory — the
 * exact set of files AGP packages into the APK — not against the source
 * declaration, so a build-config mistake that swapped, filtered or
 * transformed an asset would fail here.
 */
class SharedContentTest {

    private val sourceDir = File(
        requireNotNull(System.getProperty("ardaas.sharedContentDir")) {
            "ardaas.sharedContentDir not set — run through Gradle"
        },
    )

    private val mergedAssetsDir: File get() = BundledAssets.dir

    @Test
    fun everySharedFileIsPackagedByteForByte() {
        val sources = sourceDir.listFiles().orEmpty().filter { it.isFile }
        assertTrue("no shared content found in $sourceDir", sources.isNotEmpty())

        for (source in sources) {
            val packaged = mergedAssetsDir.resolve(source.name)
            assertTrue("${source.name} is not in the packaged assets ($mergedAssetsDir)", packaged.isFile)
            assertArrayEquals("${source.name} differs from the iOS copy", source.readBytes(), packaged.readBytes())
        }
    }
}
