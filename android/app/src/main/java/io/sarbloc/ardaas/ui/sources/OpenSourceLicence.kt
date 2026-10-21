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
 */
data class OpenSourceLicence(
    /** Asset file name of the verbatim licence text. */
    val assetName: String,
    /** What the component is, as shown in the screen's section header. */
    val component: String,
    /** Who publishes it and exactly which version is used. */
    val source: String,
) {
    /**
     * The licence text exactly as bundled. Throws if the asset is missing —
     * a build defect, not a runtime state.
     *
     * @param open opens a bundled file by name, or returns null when absent.
     */
    fun loadText(open: (fileName: String) -> InputStream?): String {
        val stream = open(assetName) ?: throw FileNotFoundException("$assetName is not bundled")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

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
            ),
        )

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
