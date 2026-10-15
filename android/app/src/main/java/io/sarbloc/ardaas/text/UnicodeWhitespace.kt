package io.sarbloc.ardaas.text

/**
 * Whitespace exactly as iOS sees it, so a benti or an occasion trims — and a
 * splice seam absorbs — the same characters on both platforms.
 *
 * iOS uses two definitions that name the same set: `CharacterSet
 * .whitespacesAndNewlines` (general category Zs, plus U+0009–U+000D, U+0085,
 * U+2028, U+2029) for trimming, and `Character.isWhitespace` (the Unicode
 * `White_Space` property of the first scalar) at the splice seams.
 *
 * Kotlin's own [Char.isWhitespace] is close but not equal, so it is not used:
 * it trims U+001C–U+001F (information separators, which iOS keeps) and keeps
 * U+0085 NEXT LINE (which iOS trims). Both agree on U+00A0 NO-BREAK SPACE and
 * the rest of Zs, and neither treats U+200B ZERO WIDTH SPACE or U+FEFF as
 * whitespace.
 *
 * Every member is in the BMP, so testing UTF-16 units one at a time is exact:
 * a surrogate is never whitespace.
 */
internal fun Char.isSwiftWhitespace(): Boolean =
    this in '\u0009'..'\u000D' ||
        this == '\u0085' ||
        this == ' ' ||
        this == ' ' ||
        Character.getType(this) == Character.SPACE_SEPARATOR.toInt()

/** Swift's `trimmingCharacters(in: .whitespacesAndNewlines)`. */
internal fun String.trimmingWhitespaceAndNewlines(): String = trim { it.isSwiftWhitespace() }
