package io.sarbloc.ardaas.app

import android.content.Context
import android.content.res.XmlResourceParser
import io.sarbloc.ardaas.R
import io.sarbloc.ardaas.persistence.ArdaasDatabase
import io.sarbloc.ardaas.translation.ModelCatalog
import io.sarbloc.ardaas.ui.reader.ReaderPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

/**
 * The backup allowlists (#121) name the files the app really writes: a
 * renamed database or preferences file would otherwise silently drop out of
 * every backup. The translation model must stay out of all of them.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRulesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val expected = setOf(
        "database" to ArdaasDatabase.FILE_NAME,
        "sharedpref" to "${ReaderPreferenceStore.FILE_NAME}.xml",
    )

    @Test
    fun cloudBackupAndDeviceTransferIncludeExactlyTheUsersData() {
        val sections = includesBySection(R.xml.data_extraction_rules)
        assertEquals(setOf("cloud-backup", "device-transfer"), sections.keys)
        for ((section, includes) in sections) assertEquals(section, expected, includes)
    }

    @Test
    fun legacyAutoBackupIncludesTheSameData() {
        assertEquals(mapOf("full-backup-content" to expected), includesBySection(R.xml.backup_rules))
    }

    @Test
    fun theTranslationModelLivesWhereNothingIsBackedUp() {
        val model = ModelCatalog.PRODUCTION.directory(context)
        assertTrue(model.path, model.startsWith(context.noBackupFilesDir))
    }

    /** `<include>` (domain, path) pairs grouped by their enclosing section; `<exclude>` would fail the equality checks. */
    private fun includesBySection(xmlRes: Int): Map<String, Set<Pair<String, String>>> {
        val result = mutableMapOf<String, MutableSet<Pair<String, String>>>()
        context.resources.getXml(xmlRes).use { parser ->
            var section = ""
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "cloud-backup", "device-transfer", "full-backup-content" -> section = parser.name
                    "data-extraction-rules" -> Unit
                    else -> result.getOrPut(section) { mutableSetOf() }.add(entry(parser))
                }
            }
        }
        return result
    }

    private fun entry(parser: XmlResourceParser): Pair<String, String> {
        val domain = parser.getAttributeValue(null, "domain")
        val path = parser.getAttributeValue(null, "path")
        val tag = if (parser.name == "include") "" else "${parser.name}:"
        return "$tag$domain" to path
    }
}
