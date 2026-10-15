package io.sarbloc.ardaas.occasion

import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Port of Ardaas/Models/OccasionCatalog.swift.

/**
 * What a slot entry names: a paath that was recited, or the occasion the
 * Ardaas marks. The picker groups by this.
 */
@Serializable
enum class OccasionCategory {
    @SerialName("paath") PAATH,
    @SerialName("occasion") OCCASION,
}

/**
 * One choice for the "….." slot.
 *
 * Every layer is authored in **genitive form**, because the slot sits inside
 * a genitive construction: SGPC reads `ਆਪ ਦੇ ਹਜ਼ੂਰ…..ਦੀ ਅਰਦਾਸ ਹੈ ਜੀ॥` and
 * Buddha Dal `ਆਪ ਜੀ ਦੇ ਹਜ਼ੂਰ…..ਦੀ ਅਰਦਾਸ…`. A bare name does not close that
 * sentence — `ਜਪੁ ਜੀ ਸਾਹਿਬ` is wrong where `ਜਪੁ ਜੀ ਸਾਹਿਬ ਦੇ ਪਾਠ` is right —
 * so each entry is a noun phrase that reads correctly followed by `ਦੀ ਅਰਦਾਸ`.
 * `OccasionCatalogTest` asserts that against the real bundled sentences.
 *
 * The same list serves both variants (operator decision on #64).
 */
@Serializable
data class Occasion(
    val id: String,
    val category: OccasionCategory,
    val gurmukhi: String,
    val transliteration: String,
    val english: String,
)

/**
 * The bundled slot list (`occasions.json`, shared with iOS).
 *
 * The Roman layer is **generated** by `GurmukhiTransliterator`, so that a
 * chosen occasion and a user's benti romanize in one voice. It is checked in
 * rather than computed at launch so it can be proof-read and diffed like any
 * other scripture-adjacent text, and pinned to the engine by
 * `bundledTransliterationMatchesTheEngine`.
 */
@Serializable
data class OccasionCatalog(
    val version: Int,
    val sources: List<String>,
    val occasions: List<Occasion>,
) {
    /** Entries of one category, in bundled order (which is display order). */
    fun occasionsIn(category: OccasionCategory): List<Occasion> = occasions.filter { it.category == category }

    /**
     * The entry with this id, or null — a saved record can name an entry a
     * later bundle has retired (see [OccasionChoice.layers]).
     */
    fun occasion(id: String): Occasion? = occasions.firstOrNull { it.id == id }

    /**
     * Structural invariants the app relies on. Throws
     * [OccasionCatalogException]. Mirrors the checks the unit tests run
     * against the real bundled JSON.
     */
    fun validate() {
        if (occasions.isEmpty()) throw OccasionCatalogException.NoOccasions
        val ids = occasions.map { it.id }
        if (ids.toSet().size != ids.size) throw OccasionCatalogException.DuplicateOccasionIds
        // The sentinel id is how a saved record says "my occasion is the free
        // text I typed" (see OccasionChoice), so no bundled entry may answer
        // to it.
        if (OccasionChoice.CUSTOM_ID in ids) {
            throw OccasionCatalogException.ReservedOccasionId(OccasionChoice.CUSTOM_ID)
        }
        // Unlike a variant's optional layers, all three are required here:
        // the picker shows whichever the reader has turned on, so a missing
        // one would render as a blank row. `isEmpty`, not blank, as on iOS.
        occasions.firstOrNull { it.gurmukhi.isEmpty() || it.transliteration.isEmpty() || it.english.isEmpty() }
            ?.let { throw OccasionCatalogException.EmptyLayer(it.id) }
    }

    companion object {
        const val FILE_NAME = "occasions.json"

        /**
         * Strict, like `ArdaasLibrary`'s decoder: an unknown key fails the
         * load where iOS's Codable would silently ignore it.
         */
        private val json = Json { ignoreUnknownKeys = false }

        /** Loads from the APK's assets. */
        fun loadBundled(assets: AssetManager): OccasionCatalog = load { name ->
            try {
                assets.open(name)
            } catch (_: FileNotFoundException) {
                null
            }
        }

        /**
         * Loads and validates the catalog. Throws — a broken bundle is a
         * build defect, not a recoverable runtime state. Callers that render
         * an Ardaas catch it and carry on with no catalog: a free-text
         * occasion survives that (see `SavedArdaas.occasionLayers`).
         *
         * @param open opens a bundled file by name, or returns null when absent.
         */
        fun load(open: (fileName: String) -> InputStream?): OccasionCatalog {
            val catalog = open(FILE_NAME)?.use { json.decodeFromString<OccasionCatalog>(it.readBytes().decodeToString()) }
                ?: throw OccasionCatalogException.ResourceMissing
            catalog.validate()
            return catalog
        }
    }
}

/** Why the bundled catalog is unusable. Data types, so tests can compare them. */
sealed class OccasionCatalogException(message: String) : Exception(message) {
    data object ResourceMissing : OccasionCatalogException("${OccasionCatalog.FILE_NAME} is not bundled")
    data object NoOccasions : OccasionCatalogException("the catalog lists no occasions")
    data object DuplicateOccasionIds : OccasionCatalogException("duplicate occasion ids")
    data class EmptyLayer(val occasionId: String) :
        OccasionCatalogException("occasion '$occasionId' has an empty layer")

    /** A bundled entry claims [OccasionChoice.CUSTOM_ID], which is reserved for the user's free text. */
    data class ReservedOccasionId(val id: String) :
        OccasionCatalogException("occasion id '$id' is reserved for free text")
}
