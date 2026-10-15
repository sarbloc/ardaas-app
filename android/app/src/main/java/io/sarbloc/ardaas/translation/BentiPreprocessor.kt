package io.sarbloc.ardaas.translation

import io.sarbloc.ardaas.text.trimmingWhitespaceAndNewlines
import io.sarbloc.ardaas.translation.IcuRegexClasses.DIGIT
import io.sarbloc.ardaas.translation.IcuRegexClasses.SPACE
import io.sarbloc.ardaas.translation.IcuRegexClasses.WORD
import io.sarbloc.ardaas.translation.IcuRegexClasses.WORD_BOUNDARY

// Port of Ardaas/Translation/BentiPreprocessor.swift. Keep the two in step:
// both must reproduce the Python reference id-for-id, and the shared fixture
// ArdaasTests/Fixtures/tokenizer_parity.json is asserted by both test suites.
//
// The reference stack, as pinned in the pipeline spec:
//   - IndicTransToolkit 1.1.1 `IndicProcessor` (eng_Latn inference path)
//   - sacremoses 0.1.1 `MosesPunctNormalizer` (lang "en", defaults) and
//     `MosesTokenizer(lang: "en").tokenize(escape: false)`
//
// Known, deliberate approximations (all invisible to English benti input,
// locked by the parity fixture), shared with iOS:
//   - Perl `IsAlnum`/`IsAlpha` (perluniprops) are approximated with
//     `\p{L}\p{M}\p{N}` / `\p{L}\p{M}`.
//   - Indic-script digit normalization (spec section 2.2) is skipped — the
//     input is English.
//   - Placeholder aliases cover the plain `<IDn>`/`[IDn]` spellings only.
// Android-specific: regex shorthands are spelled out (see IcuRegexClasses),
// and lengths are counted in code points, as Python's len() does.

/** One ordered regex substitution, mirroring one `re.sub` in the Python reference. */
private class RegexRule(pattern: String, private val template: String) {
    private val regex = Regex(pattern)

    fun apply(text: String): String = regex.replace(text, template)
}

private fun List<RegexRule>.apply(text: String): String = fold(text) { acc, rule -> rule.apply(acc) }

/**
 * English-side preprocessing for IndicTrans2: raw benti text in, the tagged
 * Moses token string the source tokenizer expects out.
 *
 * Pure and stateless. Every rule is a compile-time constant compiled once, so
 * an invalid pattern fails the first unit test that touches this object
 * rather than a user's translation.
 */
object BentiPreprocessor {

    /** An alias the model may echo back, and the original text it stands for. */
    data class Placeholder(val alias: String, val original: String)

    data class Output(
        /** `"eng_Latn pan_Guru <moses tokens joined by single spaces>"`. */
        val tagged: String,
        /** Alias → original, in insertion order. Empty for typical benti input. */
        val placeholders: List<Placeholder>,
    )

    /**
     * Full English preprocessing per spec section 2: punctuation
     * normalization → placeholder wrapping → Moses normalize + tokenize →
     * language-tag prepend.
     */
    fun preprocess(sentence: String): Output {
        // Spec section 2.2 (Indic digit normalization) intentionally skipped.
        val wrapped = wrapWithPlaceholders(puncNorm(sentence))
        val normalized = mosesPunctNormalize(wrapped.text.trimmingWhitespaceAndNewlines())
        val tokens = mosesTokenize(normalized)
        return Output(
            tagged = "eng_Latn pan_Guru " + tokens.joinToString(" "),
            placeholders = wrapped.placeholders,
        )
    }

    // region IndicProcessor._punc_norm (spec section 2.1)

    private val puncReplacements = listOf(
        RegexRule("""\r""", ""),
        RegexRule("""\([$SPACE]*""", "("),
        RegexRule("""[$SPACE]*\)""", ")"),
        RegexRule("""[$SPACE]:[$SPACE]?""", ":"),
        RegexRule("""[$SPACE];[$SPACE]?""", ";"),
        RegexRule("[`´‘‚’]", "'"),
        RegexRule("[„“”«»]", "\""),
        RegexRule("[–—]", "-"),
        RegexRule("""\.\.\.""", "..."),
        RegexRule(" %", "%"),
        RegexRule("nº ", "nº "),
        RegexRule(" ºC", " ºC"),
        // Python: lambda m: m.group(0).strip() — drops the NBSP, keeps the mark.
        RegexRule(" ([?!;])", "$1"),
        RegexRule(", ", ", "),
        // "Additional patterns" applied after the list, in this order:
        RegexRule("[ ]{2,}", " "),
        RegexRule("""\) ([\.!:?;,])""", ")$1"),
        RegexRule("""($DIGIT) %""", "$1%"),
        RegexRule(""""([,\.]+)""", "$1\""),
        RegexRule("($DIGIT) ($DIGIT)", "$1.$2"),
    )

    internal fun puncNorm(text: String): String =
        puncReplacements.apply(text).trimmingWhitespaceAndNewlines()

    // endregion

    // region IndicProcessor._wrap_with_placeholders (spec section 2.3)

    private enum class PlaceholderKind { EMAIL, URL, NUMERAL, OTHER }

    private val placeholderPatterns: List<Pair<Regex, PlaceholderKind>> = listOf(
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}""") to PlaceholderKind.EMAIL,
        Regex(
            WORD_BOUNDARY + """(?<![$WORD/.])(?:(?:https?|ftp)://)?(?:(?:[$WORD-]+\.)+(?!\.))""" +
                """(?:[$WORD/\-?#&=%.]+)+(?!\.[$WORD]+)""" + WORD_BOUNDARY,
        ) to PlaceholderKind.URL,
        Regex(
            """(~?$DIGIT+\.?$DIGIT*[$SPACE]?%?[$SPACE]?-?[$SPACE]?~?$DIGIT+\.?$DIGIT*[$SPACE]?%""" +
                """|~?$DIGIT+%""" +
                """|$DIGIT+[-/.,:']$DIGIT+[-/.,:'+]$DIGIT+(?:\.$DIGIT+)?""" +
                """|$DIGIT+[-/.:'+]$DIGIT+(?:\.$DIGIT+)?)""",
        ) to PlaceholderKind.NUMERAL,
        Regex("""[A-Za-z0-9]*[#|@][$WORD]+""") to PlaceholderKind.OTHER,
    )

    private val whitespaceRun = RegexRule("[$SPACE]+", " ")

    /** The text with every placeholder-worthy span replaced by `<IDn>`. */
    internal data class Wrapped(val text: String, val placeholders: List<Placeholder>)

    internal fun wrapWithPlaceholders(input: String): Wrapped {
        var text = input
        val placeholders = mutableListOf<Placeholder>()
        var serial = 1

        for ((pattern, kind) in placeholderPatterns) {
            // Unique matches. The Python reference iterates a set() (order
            // unspecified); here, as on iOS, first-occurrence order.
            val matches = pattern.findAll(text).map { it.value }.toCollection(LinkedHashSet())
            for (match in matches) {
                if (!isWorthWrapping(match, kind)) continue
                placeholders += aliases(serial).map { Placeholder(it, match) }
                text = text.replace(match, "<ID$serial>")
                serial += 1
            }
        }

        // Always runs in the reference (also collapses NBSP → space).
        text = whitespaceRun.apply(text).replace(">/", ">").replace("]/", "]")
        return Wrapped(text, placeholders)
    }

    /**
     * The reference's length gates on URL and numeral candidates. Python's
     * `len()` counts code points, so this does too; iOS counts grapheme
     * clusters, which differs only for a candidate carrying combining marks.
     */
    private fun isWorthWrapping(match: String, kind: PlaceholderKind): Boolean = when (kind) {
        PlaceholderKind.URL -> match.replace(".", "").codePointLength() >= 4
        PlaceholderKind.NUMERAL -> match.filterNot { it in " .:" }.codePointLength() >= 4
        PlaceholderKind.EMAIL, PlaceholderKind.OTHER -> true
    }

    private fun String.codePointLength(): Int = codePointCount(0, length)

    /** The plain alias spellings from the reference; the Indic-script ones are skipped (spec v1). */
    private fun aliases(serial: Int): List<String> = listOf(
        "<ID$serial>", "< ID$serial >", "[ID$serial]", "[ ID$serial ]",
        "[ID $serial]", "<ID$serial]", "< ID$serial]", "<ID$serial ]",
        "<id$serial>", "< id$serial >", "[id$serial]", "[ id$serial ]",
        "[id $serial]", "<id$serial]", "< id$serial]", "<id$serial ]",
    )

    // endregion

    // region sacremoses MosesPunctNormalizer
    // (lang "en", penn: true, norm_quote_commas: true, norm_numbers: true)

    private val mosesNormalizerRules = listOf(
        // EXTRA_WHITESPACE
        RegexRule("""\r""", ""),
        RegexRule("""\(""", " ("),
        RegexRule("""\)""", ") "),
        RegexRule(" +", " "),
        RegexRule("""\) ([.!:?;,])""", ")$1"),
        RegexRule("""\( """, "("),
        RegexRule(""" \)""", ")"),
        RegexRule("""($DIGIT) %""", "$1%"),
        RegexRule(" :", ":"),
        RegexRule(" ;", ";"),
        // NORMALIZE_UNICODE_IF_NOT_PENN (penn=True default)
        RegexRule("`", "'"),
        RegexRule("''", " \" "),
        // NORMALIZE_UNICODE
        RegexRule("„", "\""),
        RegexRule("“", "\""),
        RegexRule("”", "\""),
        RegexRule("–", "-"),
        RegexRule("—", " - "),
        RegexRule(" +", " "),
        RegexRule("´", "'"),
        RegexRule("([a-zA-Z])‘([a-zA-Z])", "$1'$2"),
        RegexRule("([a-zA-Z])’([a-zA-Z])", "$1'$2"),
        RegexRule("‘", "'"),
        RegexRule("‚", "'"),
        RegexRule("’", "'"),
        RegexRule("''", "\""),
        RegexRule("´´", "\""),
        RegexRule("…", "..."),
        // FRENCH_QUOTES
        RegexRule(" « ", "\""),
        RegexRule("« ", "\""),
        RegexRule("«", "\""),
        RegexRule(" » ", "\""),
        RegexRule(" »", "\""),
        RegexRule("»", "\""),
        // HANDLE_PSEUDO_SPACES
        RegexRule(" %", "%"),
        RegexRule("nº ", "nº "),
        RegexRule(" :", ":"),
        RegexRule(" ºC", " ºC"),
        RegexRule(" cm", " cm"),
        RegexRule(" \\?", "?"),
        RegexRule(" \\!", "!"),
        RegexRule(" ;", ";"),
        RegexRule(", ", ", "),
        RegexRule(" +", " "),
        // EN_QUOTATION_FOLLOWED_BY_COMMA (lang == "en")
        RegexRule(""""([,.]+)""", "$1\""),
        // OTHER (norm_numbers, non-de/es/cz/cs/fr)
        RegexRule("($DIGIT) ($DIGIT)", "$1.$2"),
    )

    internal fun mosesPunctNormalize(text: String): String =
        mosesNormalizerRules.apply(text).trimmingWhitespaceAndNewlines()

    // endregion

    // region sacremoses MosesTokenizer("en").tokenize(escape: false)

    // Perl character-class approximations (see file header).
    private const val ALNUM = """\p{L}\p{M}\p{N}"""
    private const val ALPHA = """\p{L}\p{M}"""
    private const val IS_N = """\p{N}"""

    private val dedupSpace = RegexRule("[$SPACE]+", " ")
    private val asciiJunk = RegexRule("""[\x00-\x1F]""", "")
    private val padNotAlnum = RegexRule("""([^$ALNUM$SPACE.'`,\-])""", " $1 ")

    private val commaSeparate = listOf(
        RegexRule("""([^$IS_N]),""", "$1 , "),
        RegexRule(""",([^$IS_N])""", " , $1"),
        RegexRule("""([$IS_N]),$""", "$1 , "),
    )

    private val englishApostrophe = listOf(
        RegexRule("""([^$ALPHA])'([^$ALPHA])""", "$1 ' $2"),
        RegexRule("""([^$ALPHA$IS_N])'([$ALPHA])""", "$1 ' $2"),
        RegexRule("""([$ALPHA])'([^$ALPHA])""", "$1 ' $2"),
        RegexRule("""([$ALPHA])'([$ALPHA])""", "$1 '$2"),
        RegexRule("""([$IS_N])'([s])""", "$1 '$2"),
    )

    private val multidotStart = RegexRule("""\.(\.+)""", " DOTMULTI$1")
    private val dotmultiDotChar = RegexRule("""DOTMULTI\.([^\.])""", "DOTDOTMULTI $1")
    private val dotmultiDot = RegexRule("""DOTMULTI\.""", "DOTDOTMULTI")
    private val trailingDotApostrophe = RegexRule("""\.' ?$""", " . ' ")

    internal fun mosesTokenize(input: String): List<String> {
        var text = dedupSpace.apply(input)
        text = asciiJunk.apply(text)
        text = text.trimmingWhitespaceAndNewlines()
        text = padNotAlnum.apply(text)
        text = replaceMultidots(text)
        text = commaSeparate.apply(text)
        text = englishApostrophe.apply(text)
        text = handleNonbreakingPrefixes(text)
        text = dedupSpace.apply(text).trimmingWhitespaceAndNewlines()
        text = trailingDotApostrophe.apply(text)
        text = restoreMultidots(text)
        return text.splitOnSpaces()
    }

    private fun replaceMultidots(input: String): String {
        var text = multidotStart.apply(input)
        while (text.contains("DOTMULTI.")) {
            text = dotmultiDotChar.apply(text)
            text = dotmultiDot.apply(text)
        }
        return text
    }

    private fun restoreMultidots(input: String): String {
        var text = input
        while (text.contains("DOTDOTMULTI")) {
            text = text.replace("DOTDOTMULTI", "DOTMULTI.")
        }
        return text.replace("DOTMULTI", ".")
    }

    /** Swift's `split(separator: " ")`, which drops empty pieces. */
    private fun String.splitOnSpaces(): List<String> = split(' ').filter { it.isNotEmpty() }

    // endregion

    // region Nonbreaking prefixes (sacremoses en list)

    /**
     * sacremoses `nonbreaking_prefix.en`, minus the `#NUMERIC_ONLY#` entries
     * (kept separately below, matching how the reference checks membership).
     */
    private val nonbreakingPrefixes: Set<String> = setOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "Adj", "Adm", "Adv", "Asst", "Bart", "Bldg", "Brig", "Bros", "Capt",
        "Cmdr", "Col", "Comdr", "Con", "Corp", "Cpl", "DR", "Dr", "Drs",
        "Ens", "Gen", "Gov", "Hon", "Hr", "Hosp", "Insp", "Lt", "MM", "MR",
        "MRS", "MS", "Maj", "Messrs", "Mlle", "Mme", "Mr", "Mrs", "Ms",
        "Msgr", "Op", "Ord", "Pfc", "Ph", "Prof", "Pvt", "Rep", "Reps",
        "Res", "Rev", "Rt", "Sen", "Sens", "Sfc", "Sgt", "Sr", "St", "Supt",
        "Surg", "v", "vs", "i.e", "rev", "e.g", "Rs", "Nos", "Nr",
        "Jan", "Feb", "Mar", "Apr", "Jun", "Jul", "Aug", "Sep", "Oct",
        "Nov", "Dec",
    )

    /**
     * Prefixes that only stay attached when followed by a number
     * ("No #NUMERIC_ONLY#", "Art #NUMERIC_ONLY#", "pp #NUMERIC_ONLY#").
     */
    private val numericOnlyPrefixes: Set<String> = setOf("No", "Art", "pp")

    /** Splits a sentence-final-looking `"word."` into `"word ."` unless the reference keeps it whole. */
    internal fun handleNonbreakingPrefixes(text: String): String {
        val tokens = text.splitOnSpaces().toMutableList()
        for (i in tokens.indices) {
            val token = tokens[i]
            // Python: re.search(r"^(\S+)\.$", token)
            if (!token.endsWith(".") || token.length < 2) continue
            val prefix = token.dropLast(1)
            val next = tokens.getOrNull(i + 1)
            if (keepsPeriodAttached(prefix, next)) continue
            tokens[i] = "$prefix ."
        }
        return tokens.joinToString(" ")
    }

    private fun keepsPeriodAttached(prefix: String, next: String?): Boolean {
        val firstOfNext = next?.takeIf { it.isNotEmpty() }?.codePointAt(0)
        return (prefix.contains('.') && prefix.codePoints().anyMatch(Character::isAlphabetic)) ||
            prefix in nonbreakingPrefixes ||
            (firstOfNext != null && Character.isLowerCase(firstOfNext)) ||
            (prefix in numericOnlyPrefixes && firstOfNext != null && firstOfNext in '0'.code..'9'.code)
    }

    // endregion
}
