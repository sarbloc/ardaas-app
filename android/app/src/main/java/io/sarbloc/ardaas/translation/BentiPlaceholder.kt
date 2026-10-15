package io.sarbloc.ardaas.translation

/**
 * One placeholder spelling and the source text it stands for — the Kotlin
 * shape of Swift's `(alias: String, original: String)` tuple, which
 * `BentiPreprocessor.wrapWithPlaceholders` produces and
 * [BentiPostprocessor.restorePlaceholders] consumes.
 *
 * The preprocessor hides numerals, URLs and emails behind `<IDn>` so the model
 * cannot mistranslate them, and registers every spelling the model may echo
 * back (`<ID1>`, `[ID1]`, `<id1>`, …) as a separate entry, in insertion order.
 */
data class BentiPlaceholder(val alias: String, val original: String)
