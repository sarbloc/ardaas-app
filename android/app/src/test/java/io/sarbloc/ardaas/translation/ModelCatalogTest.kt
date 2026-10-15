package io.sarbloc.ardaas.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The production catalog must stay byte-for-byte the iOS one: same pinned revision, sizes, hashes. */
class ModelCatalogTest {
    private val catalog = ModelCatalog.PRODUCTION

    @Test
    fun sizeDisclosureMatchesIos() {
        assertEquals(358_521_397L, catalog.downloadBytes)
        assertEquals(351_750_757L, catalog.graphBytes)
        assertEquals(6_770_640L, catalog.vocabularyBytes)
        assertEquals(catalog.downloadBytes + 670_000_000L, catalog.estimatedPeakDiskBytes)
        assertEquals(catalog.vocabularyBytes + 670_000_000L, catalog.estimatedInstalledBytes)
    }

    /** Computed independently (Python hashlib) over the same `name:bytes:sha256;…` string iOS digests. */
    @Test
    fun revisionMatchesTheIosDigest() {
        assertEquals("6aaa8a0a36d9571fc9d156f4cd8bf58647bb056a8445b331a53b09c837ce526f", catalog.revision)
    }

    @Test
    fun urlsResolveAgainstThePinnedCommitNotMain() {
        val encoder = catalog.artifact("encoder_model.onnx")
        // Compared as strings: URL.equals resolves hosts over DNS.
        assertEquals(
            "https://huggingface.co/naklitechie/indictrans2-en-indic-dist-200M-ONNX-int8/resolve/" +
                "e88c4f837dfbf2abecf239b5c89169dfa31580bb/encoder_model.onnx",
            encoder?.let(catalog::url)?.toString(),
        )
        assertNull(catalog.artifact("nope.onnx"))
    }

    @Test
    fun graphsAreInLoadOrderAndEveryHashIsPinned() {
        assertEquals(
            listOf("encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx"),
            catalog.graphNames,
        )
        assertTrue(catalog.artifacts.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }
}
