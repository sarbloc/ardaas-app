package io.sarbloc.ardaas.ui.sources

import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A third-party component whose licence notice ships with the app (#132).
 *
 * MIT requires the copyright and permission notice to travel with every copy,
 * so the full texts are bundled — fetched verbatim from each project at the
 * version the app uses — and shown on the Open-source licences screen. The
 * files live once, in the iOS app's `Ardaas/Licences/`, and Android packages
 * them as assets straight from there (see `sharedLicencesDir` in
 * build.gradle.kts). Port of `OpenSourceLicence.swift`; keep the two lists in step.
 *
 * These are the components whose notices are curated by hand; the Android
 * libraries the build packages are listed from generated data instead
 * ([LibraryNotice], #139).
 */
data class OpenSourceLicence(
    /** Asset file name of the verbatim licence text. */
    val assetName: String,
    /** What the component is, as shown in the screen's section header. */
    val component: String,
    /** Who publishes it and exactly which version is used. */
    val source: String,
    /**
     * Asset file name of the notices for third-party code the component
     * itself includes (#139), verbatim from the project; null if none.
     */
    val thirdPartyNoticesAssetName: String? = null,
) {
    /**
     * The licence text exactly as bundled. Throws if the asset is missing —
     * a build defect, not a runtime state.
     *
     * @param open opens a bundled file by name, or returns null when absent.
     */
    fun loadText(open: (fileName: String) -> InputStream?): String = loadAsset(assetName, open)

    companion object {
        val all: List<OpenSourceLicence> = listOf(
            OpenSourceLicence(
                assetName = "licence-indictrans2.txt",
                component = "IndicTrans2",
                source = "AI4Bharat — github.com/AI4Bharat/IndicTrans2",
            ),
            OpenSourceLicence(
                assetName = "licence-indictrans2-onnx-int8.txt",
                component = "IndicTrans2 int8 ONNX export",
                source = "naklitechie/indictrans2-en-indic-dist-200M-ONNX-int8 on Hugging Face, revision e88c4f8",
            ),
            OpenSourceLicence(
                assetName = "licence-onnxruntime.txt",
                component = "ONNX Runtime 1.24.2",
                source = "Microsoft — github.com/microsoft/onnxruntime",
                // ThirdPartyNotices.txt at tag v1.24.2: protobuf, abseil,
                // Eigen and the rest statically linked into its binaries.
                thirdPartyNoticesAssetName = "licence-onnxruntime-third-party-notices.txt",
            ),
        )

        /** The bundled file [name] as UTF-8 text; throws if it is missing. */
        fun loadAsset(name: String, open: (fileName: String) -> InputStream?): String {
            val stream = open(name) ?: throw FileNotFoundException("$name is not bundled")
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        /**
         * A third-party notices file split into its components' notices.
         * The files separate components with a line of underscores; those
         * lines are dropped, and every other line is kept in order.
         */
        fun noticeSections(raw: String): List<String> {
            val sections = mutableListOf<String>()
            val current = StringBuilder()
            fun endSection() {
                current.toString().trim('\n', '\r').takeIf { it.isNotBlank() }?.let(sections::add)
                current.clear()
            }
            for (line in raw.lines()) {
                if (SEPARATOR.matches(line)) endSection() else current.append(line).append('\n')
            }
            endSection()
            return sections
        }

        private val SEPARATOR = Regex("""\s*_{5,}\s*""")

        /**
         * [raw] as readable paragraphs for a phone-width screen. The bundled
         * texts are hard-wrapped at ~80 columns (and one is indented), which
         * breaks raggedly when re-wrapped narrower; this joins each
         * paragraph's lines with single spaces. Words and their order are
         * untouched — only line breaks and indentation change.
         */
        fun reflow(raw: String): String {
            val paragraphs = mutableListOf<String>()
            val current = mutableListOf<String>()
            fun endParagraph() {
                if (current.isNotEmpty()) {
                    paragraphs += current.joinToString(" ")
                    current.clear()
                }
            }
            for (line in raw.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) endParagraph() else current += trimmed
            }
            endParagraph()
            return paragraphs.joinToString("\n\n")
        }
    }
}
