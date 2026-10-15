package io.sarbloc.ardaas.translation

import android.content.Context
import java.io.File
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * The pinned set of artifacts the on-device English → Gurmukhi model needs —
 * port of `Ardaas/Translation/ModelCatalog.swift`.
 *
 * Every entry is pinned by exact byte count *and* SHA-256, so the download is
 * byte-accurate before it starts (the size disclosure the user is shown is a
 * sum of these, not an estimate) and verifiable after it finishes.
 *
 * A value rather than a set of constants so the installer's state machine can
 * be driven by a tiny synthetic catalog in tests. Production code always uses
 * [PRODUCTION].
 */
data class ModelCatalog(
    val artifacts: List<Artifact>,
    /** Directory URL every artifact name resolves against. Must end in `/`. */
    val repoBase: URI,
    /** Subdirectory of `noBackupFilesDir` that holds the install. */
    val directoryName: String,
    /**
     * Size of the ORT-optimized graph cache (optimized graphs plus their
     * external-initializer side-cars). An estimate rather than a pinned number
     * because ORT writes it and the exact size moves with the runtime version.
     * Only ever used for disclosure and for the free-space check — never for
     * validation, which goes by the manifest.
     */
    val estimatedOptimizedCacheBytes: Long,
) {
    enum class Kind {
        /**
         * An ONNX inference graph. Consumed once by the optimizer and deleted
         * afterwards — the optimized cache is self-sufficient.
         */
        GRAPH,

        /**
         * Tokenizer/vocabulary JSON. Needed at every translation, so it stays
         * on disk for the lifetime of the install.
         */
        VOCABULARY,
    }

    /** One pinned download. Named `Artifact` (iOS: `File`) so it never shadows [java.io.File]. */
    data class Artifact(val name: String, val bytes: Long, val sha256: String, val kind: Kind)

    // Slices

    /** The inference graphs, in load order. */
    val graphs: List<Artifact> get() = artifacts.filter { it.kind == Kind.GRAPH }
    val vocabulary: List<Artifact> get() = artifacts.filter { it.kind == Kind.VOCABULARY }
    val graphNames: List<String> get() = graphs.map { it.name }

    fun url(artifact: Artifact): URL = repoBase.resolve(artifact.name).toURL()

    fun artifact(named: String): Artifact? = artifacts.firstOrNull { it.name == named }

    // Size disclosure

    /** Exact bytes transferred over the network for a full install. Production: 358,521,397 B ≈ 358.5 MB. */
    val downloadBytes: Long get() = artifacts.sumOf { it.bytes }

    /**
     * Bytes of ONNX graph, deleted once the optimized cache is built and
     * verified. Production: 351,750,757 B ≈ 351.8 MB.
     */
    val graphBytes: Long get() = graphs.sumOf { it.bytes }

    /** Bytes that stay resident forever. Production: 6,770,640 B ≈ 6.8 MB. */
    val vocabularyBytes: Long get() = vocabulary.sumOf { it.bytes }

    /**
     * Worst-case free space needed to complete an install: the download and
     * the cache coexist until the graphs are deleted. Production: ≈ 1.03 GB.
     */
    val estimatedPeakDiskBytes: Long get() = downloadBytes + estimatedOptimizedCacheBytes

    /** Steady-state disk cost once the originals have been dropped. Production: ≈ 677 MB. */
    val estimatedInstalledBytes: Long get() = vocabularyBytes + estimatedOptimizedCacheBytes

    /**
     * Identity of this artifact set: a digest over every pinned name, size and
     * hash. Any change to the catalog changes it, which invalidates an
     * optimized cache built from the previous artifacts.
     *
     * This is what lets the original graphs be deleted after optimization: the
     * cache no longer has to fingerprint files that are gone, because the
     * downloads were SHA-256 verified against exactly these constants, so a
     * matching revision means the cache came from exactly those bytes.
     * Computed exactly as iOS does, so the two apps agree on the value.
     */
    val revision: String
        get() {
            val manifest = artifacts.joinToString(";") { "${it.name}:${it.bytes}:${it.sha256}" }
            return sha256Hex(manifest.toByteArray(Charsets.UTF_8))
        }

    /**
     * `noBackupFilesDir` is the Android analogue of iOS's backup-excluded
     * Application Support: private to the app, never shown to the user, and
     * never copied to a cloud backup — re-downloadable 600 MB has no place there.
     */
    fun directory(context: Context): File = File(context.noBackupFilesDir, directoryName)

    companion object {
        /**
         * Artifacts from `naklitechie/indictrans2-en-indic-dist-200M-ONNX-int8`
         * (IndicTrans2 en-indic dist-200M, int8 ONNX), repo main @ 2026-08-01.
         * Byte-for-byte the iOS catalog.
         */
        val PRODUCTION = ModelCatalog(
            artifacts = listOf(
                Artifact(
                    "encoder_model.onnx", 75_979_645,
                    "65726fc70e7fe73bdec5ab922caf16fe92285efe2329965894ec8143a87153f0", Kind.GRAPH,
                ),
                Artifact(
                    "decoder_model.onnx", 142_868_773,
                    "6925537cbce0576431343b33d0239b7ad442c3dd86aefe0521d8c71d60941c41", Kind.GRAPH,
                ),
                Artifact(
                    "decoder_with_past_model.onnx", 132_902_339,
                    "73d114343b59fcaa53f0909baf9ab5f13e11c4b0ce98d48404dd8b72d18ebfa1", Kind.GRAPH,
                ),
                Artifact(
                    "tokenizer_src.json", 3_380_130,
                    "913a7fe797e866de46c805d237846ff6433387e77117c377fddeb28b221808da", Kind.VOCABULARY,
                ),
                Artifact(
                    "tokenizer_meta.json", 70,
                    "9f18574a040d815695f98ac53e7d8ca18ae38b90a339eb59301690fccfc62437", Kind.VOCABULARY,
                ),
                Artifact(
                    "dict.TGT.json", 3_390_440,
                    "f7817850c9e4b99c59fad57d0611c7720f1921f215e6f247cf25d52eff7f1146", Kind.VOCABULARY,
                ),
            ),
            // Pinned to an immutable revision rather than `main`: the SHA-256s
            // above are fixed, so an upstream push to the branch would break
            // every fresh install with HashMismatch until a new app build shipped.
            repoBase = URI(
                "https://huggingface.co/naklitechie/" +
                    "indictrans2-en-indic-dist-200M-ONNX-int8/resolve/" +
                    "e88c4f837dfbf2abecf239b5c89169dfa31580bb/",
            ),
            directoryName = "BentiModel",
            // ~639 MiB, measured on the reference host.
            estimatedOptimizedCacheBytes = 670_000_000,
        )

        internal fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
