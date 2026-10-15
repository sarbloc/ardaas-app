package io.sarbloc.ardaas.ui.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import io.sarbloc.ardaas.content.LayerKind
import io.sarbloc.ardaas.ui.ReaderTextStyles

/**
 * [ReaderTextStyles] at the reader's chosen text size. Line height scales with
 * the type — as iOS scales its `lineSpacing` with `fontScale` — so a larger
 * size keeps the same generous leading rather than tightening it.
 */
@Immutable
internal data class ScaledReaderStyles(
    val gurmukhi: TextStyle,
    val transliteration: TextStyle,
    val translation: TextStyle,
) {
    /** The style for one layer, used for canonical segments and the benti alike. */
    fun forLayer(kind: LayerKind): TextStyle = when (kind) {
        LayerKind.GURMUKHI -> gurmukhi
        LayerKind.TRANSLITERATION -> transliteration
        LayerKind.ENGLISH -> translation
    }

    companion object {
        fun at(scale: Double): ScaledReaderStyles = ScaledReaderStyles(
            gurmukhi = ReaderTextStyles.Gurmukhi.scaledBy(scale),
            transliteration = ReaderTextStyles.Transliteration.scaledBy(scale),
            translation = ReaderTextStyles.Translation.scaledBy(scale),
        )
    }
}

internal fun TextStyle.scaledBy(scale: Double): TextStyle {
    val factor = scale.toFloat()
    return copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)
}
