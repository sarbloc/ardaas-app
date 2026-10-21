package io.sarbloc.ardaas.ui.sources

import io.sarbloc.ardaas.content.BundledAssets
import io.sarbloc.ardaas.translation.ModelOptimizer
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The licence notices Android shows are the ones it packages, byte for byte
 * the files in `Ardaas/Licences/`, each carrying its component's copyright.
 * Reads the MERGED assets directory, as SharedContentTest does.
 */
class OpenSourceLicenceTest {

    private val sourceDir = File(
        requireNotNull(System.getProperty("ardaas.sharedLicencesDir")) {
            "ardaas.sharedLicencesDir not set — run through Gradle"
        },
    )

    /** The copyright line each notice must carry, by asset name. */
    private val expectedCopyright = mapOf(
        "licence-indictrans2.txt" to "Copyright (c) AI4Bharat.",
        "licence-indictrans2-onnx-int8.txt" to "Copyright (c) AI4Bharat.",
        "licence-onnxruntime.txt" to "Copyright (c) Microsoft Corporation",
    )

    @Test
    fun everyLicenceIsPackagedWithItsCopyrightAndPermissionNotice() {
        assertEquals(expectedCopyright.keys, OpenSourceLicence.all.map { it.assetName }.toSet())
        for (licence in OpenSourceLicence.all) {
            val text = licence.loadText(BundledAssets::open)
            assertTrue("${licence.assetName} lacks its copyright line", text.contains(expectedCopyright.getValue(licence.assetName)))
            assertTrue("${licence.assetName} is not the MIT licence", text.trimStart().startsWith("MIT License"))
            assertTrue(
                "${licence.assetName} lacks the permission notice",
                text.contains("The above copyright notice and this permission notice shall be included in all"),
            )
        }
    }

    @Test
    fun packagedLicencesAreTheSharedFilesByteForByte() {
        for (name in OpenSourceLicence.all.flatMap { listOfNotNull(it.assetName, it.thirdPartyNoticesAssetName) }) {
            val source = sourceDir.resolve(name)
            val packaged = BundledAssets.dir.resolve(name)
            assertTrue("$name is not in the packaged assets", packaged.isFile)
            assertArrayEquals("$name differs from the shared copy", source.readBytes(), packaged.readBytes())
        }
    }

    @Test
    fun onnxRuntimeShipsItsThirdPartyNoticesForTheVersionInUse() {
        val ort = OpenSourceLicence.all.single { it.assetName == "licence-onnxruntime.txt" }
        assertTrue(ort.component, ort.component.endsWith(" ${ModelOptimizer.ORT_PACKAGE_VERSION}"))
        val name = requireNotNull(ort.thirdPartyNoticesAssetName)
        val raw = OpenSourceLicence.loadAsset(name, BundledAssets::open)
        assertTrue(raw.startsWith("THIRD PARTY SOFTWARE NOTICES AND INFORMATION"))
        val sections = OpenSourceLicence.noticeSections(raw)
        // Spot-check components the native binaries link statically.
        for (component in listOf("protocolbuffers/protobuf", "Eigen", "abseil")) {
            assertTrue("no notice for $component", sections.any { it.contains(component) })
        }
    }

    @Test
    fun noticeSectionsSplitOnUnderscoreRulesOnly() {
        val raw = "Preamble\nline two\n\n_____\n\nFirst component\n\nIts licence ___ inline\n_____\nSecond\n  _____  \n\n"
        assertEquals(
            listOf("Preamble\nline two", "First component\n\nIts licence ___ inline", "Second"),
            OpenSourceLicence.noticeSections(raw),
        )
    }

    @Test
    fun noticeSectionsKeepEveryWordOfTheRealNotices() {
        val name = requireNotNull(OpenSourceLicence.all.firstNotNullOfOrNull { it.thirdPartyNoticesAssetName })
        val raw = OpenSourceLicence.loadAsset(name, BundledAssets::open)
        val words = { s: String -> s.split(Regex("\\s+")).filter { it.isNotEmpty() && !Regex("_{5,}").matches(it) } }
        val sections = OpenSourceLicence.noticeSections(raw)
        assertEquals(words(raw), words(sections.joinToString("\n")))
        assertEquals(words(raw), sections.flatMap { words(OpenSourceLicence.reflow(it)) })
    }

    @Test(expected = FileNotFoundException::class)
    fun aMissingAssetIsAnError() {
        OpenSourceLicence("nope.txt", "Nothing", "nowhere").loadText { null }
    }

    @Test
    fun reflowJoinsHardWrappedLinesAndDropsIndentation() {
        val raw = "    MIT License\n\n    Copyright (c) Someone.\n\n    Permission is hereby\n    granted, free\r\n    of charge.\n\n\n    THE END"
        assertEquals(
            "MIT License\n\nCopyright (c) Someone.\n\nPermission is hereby granted, free of charge.\n\nTHE END",
            OpenSourceLicence.reflow(raw),
        )
    }

    @Test
    fun reflowKeepsEveryWordOfARealNotice() {
        for (licence in OpenSourceLicence.all) {
            val raw = licence.loadText(BundledAssets::open)
            val words = { s: String -> s.split(Regex("\\s+")).filter(String::isNotEmpty) }
            assertEquals(licence.assetName, words(raw), words(OpenSourceLicence.reflow(raw)))
        }
    }
}
