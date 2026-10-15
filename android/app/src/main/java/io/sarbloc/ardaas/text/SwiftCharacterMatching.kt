package io.sarbloc.ardaas.text

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

// Swift string matching, reproduced on top of Kotlin's UTF-16 strings.
//
// Swift's `String.replacingOccurrences(of:with:)` (no options) and `==`
// compare `Character`s — extended grapheme clusters — under canonical
// equivalence. Kotlin's `replace` and `==` compare UTF-16 units. The two
// disagree exactly where model output can be odd:
//
// - a match must start and end on a grapheme boundary, so "." followed by a
//   combining mark is not a "." to Swift, and U+2581 followed by a vowel sign
//   is not replaced;
// - canonically equivalent spellings match, so U+1FEF GREEK VARIA (which
//   decomposes to U+0060) *is* a backtick to Swift.
//
// Graphemes come from java.text.BreakIterator, which is extended-grapheme
// aware (ICU on Android, JDK 20+ on the host).

/** The string's `Character`s as Swift sees them: extended grapheme clusters, in order. */
internal fun String.swiftCharacters(): List<String> {
    val clusters = BreakIterator.getCharacterInstance(Locale.ROOT)
    clusters.setText(this)
    val characters = ArrayList<String>(length)
    var start = clusters.first()
    var end = clusters.next()
    while (end != BreakIterator.DONE) {
        characters += substring(start, end)
        start = end
        end = clusters.next()
    }
    return characters
}

/** Swift's `String ==`: canonical equivalence, not code-unit equality. */
internal fun String.canonicallyEquals(other: String): Boolean =
    this == other || nfd(this) == nfd(other)

/**
 * Swift's `replacingOccurrences(of:with:)` with no options: non-overlapping,
 * left to right, matching whole `Character`s under canonical equivalence.
 * An empty [target] matches nothing, as in Foundation.
 */
internal fun String.swiftReplacingOccurrences(target: String, replacement: String): String {
    if (target.isEmpty()) return this
    val pattern = target.swiftCharacters().map(::nfd)
    val characters = swiftCharacters()
    val keys = characters.map(::nfd)
    val out = StringBuilder(length)
    var i = 0
    while (i < characters.size) {
        if (matchesAt(keys, i, pattern)) {
            out.append(replacement)
            i += pattern.size
        } else {
            out.append(characters[i])
            i += 1
        }
    }
    return out.toString()
}

private fun matchesAt(keys: List<String>, start: Int, pattern: List<String>): Boolean {
    if (start + pattern.size > keys.size) return false
    return pattern.indices.all { keys[start + it] == pattern[it] }
}

private fun nfd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
