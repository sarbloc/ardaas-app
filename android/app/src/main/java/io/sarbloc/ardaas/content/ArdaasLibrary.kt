package io.sarbloc.ardaas.content

import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Port of Ardaas/Models/ArdaasLibrary.swift.

/** One Ardaas variant: identity plus its full text content. */
data class ArdaasVariant(
    val id: String,
    val displayName: String,
    val content: ArdaasContent,
)

/**
 * The bundled collection of variants, driven by `variants.json`. Manifest
 * order is DISPLAY order only — the default is pinned to [DEFAULT_VARIANT_ID]
 * by policy, so reordering the manifest can never silently change what new
 * saves or unknown-id fallbacks resolve to.
 */
data class ArdaasLibrary(val variants: List<ArdaasVariant>) {

    /** [validate] guarantees the default exists; `variants[0]` only keeps this non-null. */
    val defaultVariant: ArdaasVariant
        get() = variants.firstOrNull { it.id == DEFAULT_VARIANT_ID } ?: variants[0]

    fun variant(id: String): ArdaasVariant? = variants.firstOrNull { it.id == id }

    /** A saved record's variant, falling back to the default when the id is unknown. */
    fun resolvedVariant(id: String): ArdaasVariant = variant(id) ?: defaultVariant

    fun validate() {
        if (variants.isEmpty()) throw ArdaasLibraryException.NoVariants
        val ids = variants.map { it.id }
        if (ids.toSet().size != ids.size) throw ArdaasLibraryException.DuplicateVariantIds
        if (DEFAULT_VARIANT_ID !in ids) throw ArdaasLibraryException.MissingDefaultVariant
    }

    companion object {
        /** Pinned, not derived from manifest order. Matches iOS. */
        const val DEFAULT_VARIANT_ID = "sgpc"

        /**
         * Strict on purpose: an unknown key fails the load. iOS's Codable
         * ignores unknown keys, so a typo like `occassionSlot` decodes there
         * as "no slot" and is caught only by a test. Here it fails outright —
         * and since Android CI runs on every change to the shared resources,
         * that failure lands on the same PR as the typo.
         */
        private val json = Json { ignoreUnknownKeys = false }

        /** Loads from the APK's assets. */
        fun loadBundled(assets: AssetManager): ArdaasLibrary = load { name ->
            try {
                assets.open(name)
            } catch (_: FileNotFoundException) {
                null
            }
        }

        /**
         * Loads and validates the manifest and every variant it lists.
         * Throws — a broken bundle is a build defect, not a recoverable
         * runtime state.
         *
         * @param open opens a bundled file by name, or returns null when absent.
         */
        fun load(open: (fileName: String) -> InputStream?): ArdaasLibrary {
            val manifest = open("variants.json")?.use { json.decodeFromString<Manifest>(it.readText()) }
                ?: throw ArdaasLibraryException.ManifestMissing

            val variants = manifest.variants.map { entry ->
                val content = open("${entry.resource}.json")
                    ?.use { json.decodeFromString<ArdaasContent>(it.readText()) }
                    ?: throw ArdaasLibraryException.VariantResourceMissing(entry.id, entry.resource)
                content.validate()
                ArdaasVariant(entry.id, entry.displayName, content)
            }
            return ArdaasLibrary(variants).also { it.validate() }
        }

        private fun InputStream.readText(): String = readBytes().decodeToString()
    }

    @Serializable
    private data class Manifest(val version: Int, val variants: List<Entry>) {
        @Serializable
        data class Entry(val id: String, val displayName: String, val resource: String)
    }
}

sealed class ArdaasLibraryException(message: String) : Exception(message) {
    data object ManifestMissing : ArdaasLibraryException("variants.json is not bundled")
    data object NoVariants : ArdaasLibraryException("the manifest lists no variants")
    data object DuplicateVariantIds : ArdaasLibraryException("duplicate variant ids")
    data object MissingDefaultVariant :
        ArdaasLibraryException("the default variant '${ArdaasLibrary.DEFAULT_VARIANT_ID}' is missing")
    data class VariantResourceMissing(val id: String, val resource: String) :
        ArdaasLibraryException("variant '$id' names resource '$resource', which is not bundled")
}
