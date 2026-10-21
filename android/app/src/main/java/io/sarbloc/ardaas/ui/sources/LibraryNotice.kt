package io.sarbloc.ardaas.ui.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A library the Android build packages, with the full text of each of its
 * licences (#139). Collected at build time by the AboutLibraries Gradle
 * plugin into `res/raw/aboutlibraries.json` — see "Library notices" in
 * build.gradle.kts for what the build guarantees about it.
 */
data class LibraryNotice(
    /** Maven `group:artifact`. */
    val id: String,
    val name: String,
    val version: String?,
    val licences: List<Licence>,
) {
    data class Licence(val name: String, val text: String)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Every library in the plugin's JSON, sorted by name. Throws on
         * malformed JSON or a library naming a licence the file does not
         * carry: both are build defects, and an error says so where an empty
         * list would quietly drop notices.
         */
        fun parse(raw: String): List<LibraryNotice> {
            val definitions = json.decodeFromString<Definitions>(raw)
            return definitions.libraries.map { library ->
                LibraryNotice(
                    id = library.uniqueId,
                    name = library.name?.takeIf { it.isNotBlank() } ?: library.uniqueId,
                    version = library.artifactVersion,
                    licences = library.licenses.map { key ->
                        val licence = checkNotNull(definitions.licenses[key]) {
                            "${library.uniqueId} names licence '$key', which the definitions lack"
                        }
                        Licence(licence.name, licence.content.orEmpty())
                    },
                )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LibraryNotice::name).thenBy(LibraryNotice::id))
        }
    }

    /** The subset of the plugin's output format this screen reads. */
    @Serializable
    private class Definitions(
        val libraries: List<Library>,
        val licenses: Map<String, LicenceDefinition>,
    )

    @Serializable
    private class Library(
        val uniqueId: String,
        val artifactVersion: String? = null,
        val name: String? = null,
        val licenses: List<String> = emptyList(),
    )

    @Serializable
    private class LicenceDefinition(
        val name: String,
        val content: String? = null,
    )
}
