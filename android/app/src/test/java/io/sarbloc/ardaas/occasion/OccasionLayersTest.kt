package io.sarbloc.ardaas.occasion

import io.sarbloc.ardaas.content.LayerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the "Layers" section of ArdaasTests/OccasionChoiceTests.swift — the
 * tests that cover only the plain data type. The rest of that file is in
 * [OccasionChoiceTest].
 */
class OccasionLayersTest {

    @Test
    fun layersAreTrimmedAndSubscriptable() {
        val layers = OccasionLayers(gurmukhi = "  ਪਾਠ\n", transliteration = " Paath ", english = " a paath ")
        assertEquals("ਪਾਠ", layers[LayerKind.GURMUKHI])
        assertEquals("Paath", layers[LayerKind.TRANSLITERATION])
        assertEquals("a paath", layers[LayerKind.ENGLISH])
        assertFalse(layers.isEmpty)
    }

    @Test
    fun emptyLayersAreEmpty() {
        assertTrue(OccasionLayers().isEmpty)
        assertTrue(OccasionLayers(gurmukhi = " ", transliteration = "\n", english = "\t").isEmpty)
        assertFalse(OccasionLayers(english = "x").isEmpty)
    }
}
