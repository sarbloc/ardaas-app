package io.sarbloc.ardaas.translation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The ORT graph rewrite's failure paths, on the host's real ONNX Runtime — so
 * every CI run also proves the runtime loads on the unit-test classpath. The
 * success path needs the real graphs and lives in BentiTranslatorEndToEndTest.
 */
class OnnxGraphOptimizerTest {
    @get:Rule val temp = TemporaryFolder()

    /**
     * An optimized graph is only guaranteed loadable by the ORT build that
     * wrote it, so the cache key must name the runtime actually linked. The
     * host artifact shares the Android one's version.ref, so this pins the
     * version catalog to the constant.
     */
    @Test
    fun cacheKeyNamesTheLinkedRuntime() {
        assertEquals(ModelOptimizer.ORT_PACKAGE_VERSION, ortEnvironment().version)
    }

    @Test
    fun isAvailable() {
        assertTrue(OnnxGraphOptimizer.isAvailable)
    }

    @Test
    fun missingSourceIsAMissingFile() {
        val error = assertThrows(TranslationError.MissingModelFile::class.java) {
            OnnxGraphOptimizer.optimize(File(temp.root, "encoder_model.onnx"), File(temp.root, "out.onnx"), "out.onnx.data")
        }
        assertEquals("encoder_model.onnx", error.name)
    }

    @Test
    fun aGraphOrtCannotParseFailsAsThatGraph() {
        val source = temp.newFile("decoder_model.onnx").apply { writeText("not a protobuf") }
        val destination = File(temp.root, "Optimized/decoder_model.onnx")

        val error = assertThrows(TranslationError.OptimizationFailed::class.java) {
            OnnxGraphOptimizer.optimize(source, destination, "decoder_model.onnx.data")
        }
        assertEquals("decoder_model.onnx", error.graph)
        assertFalse(destination.exists())
    }

    @Test
    fun anUnloadableGraphFailsTheLoadBackCheck() {
        val graph = temp.newFile("decoder_with_past_model.onnx").apply { writeText("truncated") }

        val error = assertThrows(TranslationError.OptimizationFailed::class.java) {
            OnnxGraphOptimizer.verifyLoadable(graph)
        }
        assertEquals("decoder_with_past_model.onnx", error.graph)
    }
}
