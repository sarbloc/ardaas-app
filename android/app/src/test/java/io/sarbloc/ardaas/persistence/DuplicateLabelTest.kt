package io.sarbloc.ardaas.persistence

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the label half of ArdaasTests/DuplicateArdaasTests.swift (#159). */
class DuplicateLabelTest {

    @Serializable
    private data class ParityFixture(val cases: List<Case>) {
        @Serializable
        data class Case(val name: String, val label: String, val existing: List<String>, val expected: String)
    }

    /**
     * The iOS suite asserts the same file
     * (`ArdaasTests/Fixtures/duplicate-label-parity.json`), so the two apps
     * name a copy identically. Never edit an expected value to make one side
     * pass.
     */
    @Test
    fun sharedParityFixture() {
        val path = requireNotNull(System.getProperty("ardaas.duplicateLabelParityFixture")) {
            "ardaas.duplicateLabelParityFixture not set — run through Gradle"
        }
        val fixture = Json { ignoreUnknownKeys = true }
            .decodeFromString<ParityFixture>(File(path).readText(Charsets.UTF_8))
        assertTrue("only ${fixture.cases.size} cases", fixture.cases.size >= 30)
        for (case in fixture.cases) {
            // Kotlin compares Strings by code unit, so this is scalar-exact.
            assertEquals(case.name, case.expected, DuplicateLabel.forCopyOf(case.label, case.existing))
        }
    }

    @Test
    fun duplicatingRepeatedlyCountsUp() {
        val labels = mutableListOf("Family")
        repeat(4) { labels += DuplicateLabel.forCopyOf(labels.last(), labels) }
        assertEquals(listOf("Family", "Family copy", "Family copy 2", "Family copy 3", "Family copy 4"), labels)
    }

    @Test
    fun theBaseStripsOnlyACopySuffix() {
        assertEquals("Family", DuplicateLabel.base(" Family copy 12 "))
        assertEquals("Family", DuplicateLabel.base("Family COPY"))
        assertEquals("Family copy", DuplicateLabel.base("Family copy copy"))
        assertEquals("Family", DuplicateLabel.base("Family"))
        assertEquals("copy", DuplicateLabel.base("copy"))
    }
}
