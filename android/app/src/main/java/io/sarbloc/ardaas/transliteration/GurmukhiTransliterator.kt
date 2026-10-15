package io.sarbloc.ardaas.transliteration

import io.sarbloc.ardaas.text.isSwiftWhitespace
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

// Port of Ardaas/Models/GurmukhiTransliterator.swift. Keep the two in step:
// the Buddha Dal and occasions Roman layers are engine output pinned on both
// platforms, so one rule drifting would make the same benti romanize
// differently on iOS and Android. The shared fixture
// ArdaasTests/Fixtures/transliteration-parity.json is asserted by both test
// suites so a divergence fails a build rather than shipping.
//
// String semantics: the Swift engine walks Unicode scalars, which map to code
// points here (never UTF-16 units). Where the Swift code reads a Character (a
// grapheme cluster) or compares Strings (canonical equivalence), this port
// reproduces that explicitly — see lastCharacterIsWhitespace and lexiconKey.

/**
 * Deterministic, rule-based Gurmukhi → Roman transliteration.
 *
 * Pure, synchronous and stateless: the same input always produces the same
 * output, with no I/O, no model and no dependencies. It exists for two jobs:
 *
 * 1. romanizing a user-authored benti, which is free Punjabi text; and
 * 2. generating a transliteration layer for variants that ship without one
 *    (e.g. the Buddha Dal text).
 *
 * ## Scheme
 *
 * The target is the plain-ASCII, "Gurbani-style" romanization already used by
 * the bundled SGPC layer — `Waheguru Ji Ki Fateh ||`, not ISO 15919's
 * `vāhigurū jī kī fatahi`. No diacritics, no retroflex/dental distinction, one
 * Title-Cased word per Gurmukhi word.
 *
 * The bundled SGPC transliteration is human-authored and internally
 * inconsistent (`ਸਭ ਥਾਂਈ` appears as both "Sabh Thaai" and "Sabh Thai"), so it
 * is a style reference, **not** a fitting target. This engine aims at the same
 * house style while being internally consistent.
 *
 * ### Consonants
 *
 * `ਕ k · ਖ kh · ਗ g · ਘ gh · ਙ ng · ਚ ch · ਛ chh · ਜ j · ਝ jh · ਞ nj ·`
 * `ਟ t · ਠ th · ਡ d · ਢ dh · ਣ n · ਤ t · ਥ th · ਦ d · ਧ dh · ਨ n ·`
 * `ਪ p · ਫ ph · ਬ b · ਭ bh · ਮ m · ਯ y · ਰ r · ਲ l · ਵ v · ੜ rh`
 *
 * Pairin-bindi (nukta) letters: `ਸ਼ sh · ਖ਼ kh · ਗ਼ gh · ਜ਼ z · ਫ਼ f · ਲ਼ l`.
 * Both the precomposed letters (U+0A36, U+0A59…U+0A5E) and the decomposed
 * base + U+0A3C NUKTA spellings used in the bundled JSON are accepted.
 *
 * Retroflex and dental series collapse (`ਟ` and `ਤ` are both `t`) because the
 * house style is plain ASCII.
 *
 * ### Vowels
 *
 * `ਅ a · ਆ/ਾ aa · ਇ/ਿ i · ਈ/ੀ i · ਉ/ੁ u · ਊ/ੂ u · ਏ/ੇ e · ਐ/ੈ ai ·`
 * `ਓ/ੋ o · ਔ/ੌ au`. The bearers ੳ ਅ ੲ carry whichever matra follows them; a
 * bare ੳ with no matra is `o`.
 *
 * Only the `a`/`aa` length contrast is written. `i`/`u` length is not, which
 * is what the house style does in its most common forms: `Ji`, `Ki`, `Sri`,
 * `Guru` — not `Jee`, `Kee`, `Sree`, `Guroo`.
 *
 * A glide is inserted before a vowel-initial syllable that follows an `i` or
 * `u`: `ਧਿਆਨ` → `Dhiyaan`, `ਸੁਆਸ` → `Suwaas`.
 *
 * An independent ਉ that opens its own syllable this way — i.e. one that takes
 * a glide — is read `o` rather than `u`, and is written `ou` so that it stays
 * distinct from ਓ: `ਜੀਉ` → `Jiyou`, where `ਜੀਓ` → `Jiyo`. Elsewhere ਉ is the
 * second half of a plain vowel juncture (`ਨਉ` → `Nau`, `ਨਾਉ` → `Naau`,
 * `ਭਗਉਤੀ` → `Bhagauti`) or a word-initial `u` (`ਉਪਦੇਸ਼` → `Updesh`), and those
 * keep `u`.
 *
 * ### Nasalization, gemination, conjuncts
 *
 * Tippi (ੰ) is a full nasal consonant: `n`, or `m` before a labial
 * (`ਪ ਫ ਬ ਭ ਮ ਫ਼`) — `ਪੰਥ` → `Panth`, `ਸਿੰਘ` → `Singh`, `ਅੰਮ੍ਰਿਤਸਰ` →
 * `Amritsar`. When the nasal letter equals the following consonant it is
 * written once rather than doubled (`ਮੰਨ` → `Man`, not `Mann`).
 *
 * Bindi (ਂ, and the rare adak bindi ਁ) marks a *nasalized vowel* rather than a
 * nasal consonant, so it is written parenthesised and never assimilates:
 * `ਸਿੰਘਾਂ` → `Singhaa(n)`, `ਲਈਂ` → `Lai(n)`, `ਥਾਂਈ` → `Thaa(n)i`. The
 * parentheses are what keep the two signs apart in Roman — `Singh` is the
 * name, `Singhaa(n)` its oblique plural.
 *
 * Addak (ੱ) geminates the following consonant by doubling its first Roman
 * letter (`ਦਿੱਤੇ` → `Ditte`, `ਸੱਚੇ` → `Sacche`), with two readability
 * exceptions: aspirates are not doubled (`ਸਿੱਖੀ` → `Sikhi`, not `Sikkhi`) and
 * a gemination that would land word-final after schwa deletion is written
 * single (`ਸਰਬੱਤ` → `Sarbat`, `ਸਿੱਖ` → `Sikh`).
 *
 * Subscript conjuncts (੍ + ਹ/ਰ/ਵ, i.e. pairin haha/rara/wawa) join the onset:
 * `ਪ੍ਰ` → `pr`, `ਕ੍ਰਿ` → `kri`, `ਤਿਨ੍ਹਾਂ` → `Tinhaan`. A pairin haha after a
 * consonant whose Roman already ends in `h` is absorbed (`ਚੜ੍ਹਦੀ` →
 * `Charhdi`, not `Charhhdi`).
 *
 * ### ਹ and the vowel it carries
 *
 * ਹ is weak: it never hosts a deleted vowel, and it colours a preceding
 * inherent schwa. Two consequences, both applied before schwa deletion:
 *
 * - **A schwa before a word-final ਹ + sihari coalesces into `eh`.** A word
 *   ending in ਹਿ is read /-ɛh/, not /-əhi/, so the schwa becomes `e` and the
 *   ਹ closes the syllable: `ਪਹਿ` → `Peh`, `ਮਹਿ` → `Meh`, `ਕਹਿ` → `Keh`.
 *   Two things narrow it. It is **word-final only** — mid-word ਹਿ takes the
 *   ordinary rules, so `ਸਹਿਤ` → `Sahit` and `ਪਹਿਲਾ` → `Pahilaa` — and the
 *   trigger is specifically the inherent schwa, so a written vowel before ਹ
 *   is unaffected and `ਸਾਹਿਬ` stays `Saahib`, `ਬੋਹਿਥ` stays `Bohith`.
 *   (`ਵਾਹਿਗੁਰੂ` → `Waheguru` shows the same `e` after a long vowel, but that
 *   is a lexicalized reduction, so it lives in the lexicon below rather than
 *   in this rule.)
 * - **A short vowel on ਹ is never elided** by rule 1 below: `ਕਹੁ` → `Kahu`,
 *   not `Kah`.
 *
 * Restricting the first rule to word-final position is what keeps the tatsama
 * borrowings that hold their /əhi/ intact: `ਸਹਿਤ` reads `Sahit`. The words
 * that *are* reduced mid-word are lexical, not predictable from the spelling,
 * so they go in the lexicon: `ਰਹਿਤ` → `Rehat`, `ਰਹਿਰਾਸ` → `Reharaas`,
 * `ਸਹਿਜ` → `Sehaj`.
 *
 * ### Schwa deletion
 *
 * The single biggest quality lever. Three rules, applied per word:
 *
 * 1. **Final short-vowel elision.** A word-final sihari (ਿ) or aunkar (ੁ) is
 *    silent, the standard Gurbani reading convention:
 *    `ਸਿਮਰਿ` → `Simar`, `ਸਤਿ` → `Sat`, `ਅਮਰਦਾਸੁ` → `Amardaas`. Not on ਹ,
 *    per the section above.
 * 2. **Final schwa deletion.** The inherent `a` of a word-final consonant is
 *    dropped: `ਨਾਨਕ` → `Naanak`, not `Naanaka`.
 * 3. **Medial schwa deletion.** Scanning right to left, the inherent `a` of a
 *    non-initial, non-final syllable is dropped when both neighbours still
 *    have a pronounced vowel (the classic `VCəCV → VCCV` rule):
 *    `ਪਾਤਸ਼ਾਹੀ` → `Paatshaahi`, `ਹਰਗੋਬਿੰਦ` → `Hargobind`, `ਅਰਦਾਸ` → `Ardaas`.
 *    The first syllable of a word is never emptied, a syllable carrying a
 *    nasal keeps its vowel, nothing is deleted before a vowel-initial
 *    syllable, where it would merge two syllables (`ਭਗਉਤੀ` → `Bhagauti`, not
 *    `Bhaguti`), and nothing is deleted before a conjunct cluster, where it
 *    would pile up three consonants (`ਸਮਗ੍ਰੀ` → `Samagri`, not `Samgri`).
 *
 * Known limitations of the rule: it is orthographic, so it cannot know about
 * morpheme boundaries (compounds may keep or lose a schwa a reader would
 * place differently), it does not restore the schwa that Punjabi keeps before
 * certain sonorant clusters, and rule 1 will wrongly silence a final short
 * vowel in the rare modern word that genuinely ends in one. Where two
 * deletable schwas are adjacent only the rightmost goes, so `ਗੁਰਦਵਾਰਿਆਂ`
 * comes out `Guradvaariyaa(n)`.
 *
 * A vowel-vowel juncture with no glide is written as-is, so `ਲਈਂ` → `Lai(n)`
 * can be misread as the `ai` of ਐ. Writing it `La-i(n)` would be no clearer,
 * and the house style itself writes such junctures plain ("Nau", "Bhagauti").
 *
 * ### Everything else
 *
 * `ੴ` → `Ik-Onkar`; `॥` → `||` and `।` → `|`. These are standalone tokens:
 * each is separated from its neighbours by one space, even when the source
 * runs them into an adjacent word or Latin text (`ਜੀ॥ਸਤਿ` → `Ji || Sat`,
 * `ਜੀ॥hello` → `Ji || hello`), while an existing space is never doubled and
 * following punctuation is left alone (`ਜੀ॥.` → `Ji ||.`).
 * Gurmukhi digits ੦–੯ → ASCII `0`–`9`.
 * Line breaks, spacing and any Latin/ASCII already in the input pass through
 * untouched and un-recased, so a mixed-script benti survives intact.
 * Udaat (ੑ) and yakash (ੵ) are dropped; visarga (ਃ) renders as `h`.
 *
 * ### Exception lexicon
 *
 * A deliberately small table of high-frequency words whose rule output reads
 * wrong (`ਵਾਹਿਗੁਰੂ` → `Waheguru`, not `Vaahiguru`). It is a quality patch, not
 * a dictionary: see [lexicon]. Words already produced correctly by the rules
 * (`ਸਿੰਘ`, `ਅਰਦਾਸ`) are pinned there too, so that a later rule change cannot
 * silently alter them.
 */
object GurmukhiTransliterator {

    // region Public API

    /** Transliterates Gurmukhi text to Roman, leaving non-Gurmukhi input as-is. */
    fun transliterate(gurmukhi: String): String {
        val output = StringBuilder()
        val word = ArrayList<Int>()
        // ੴ and the dandas are standalone tokens: they are set off by a space
        // even when the source runs them into the next word ("ਜੀ॥ਸਤਿ").
        var afterStandaloneToken = false

        fun separate() {
            if (afterStandaloneToken && output.isNotEmpty() && !lastCharacterIsWhitespace(output)) {
                output.append(' ')
            }
        }

        fun flushWord() {
            if (word.isEmpty()) return
            separate()
            output.append(transliterateWord(word.toIntArray()))
            word.clear()
            afterStandaloneToken = false
        }

        var offset = 0
        while (offset < gurmukhi.length) {
            val scalar = gurmukhi.codePointAt(offset)
            offset += Character.charCount(scalar)

            if (isWordScalar(scalar)) {
                word.add(scalar)
                continue
            }
            flushWord()

            when (scalar) {
                0x0A74 -> { // ੴ
                    separate()
                    output.append("Ik-Onkar")
                    afterStandaloneToken = true
                }
                0x0964, 0x0965 -> { // । ॥
                    if (output.isNotEmpty() && !lastCharacterIsWhitespace(output)) {
                        output.append(' ')
                    }
                    output.append(if (scalar == 0x0965) "||" else "|")
                    afterStandaloneToken = true
                }
                in 0x0A66..0x0A6F -> { // Gurmukhi digits
                    separate()
                    output.append(ASCII_DIGITS[scalar - 0x0A66])
                    afterStandaloneToken = false
                }
                else -> {
                    // Latin/ASCII running straight into a standalone token
                    // ("ਜੀ॥hello") is separated too, but punctuation is left
                    // alone so "ਜੀ॥." doesn't become "Ji || .".
                    if (afterStandaloneToken && isLetterOrNumber(scalar)) {
                        separate()
                    }
                    output.appendCodePoint(scalar)
                    afterStandaloneToken = false
                }
            }
        }
        flushWord()
        return output.toString()
    }

    // endregion

    // region Word assembly

    private fun transliterateWord(word: IntArray): String {
        lexicon[lexiconKey(canonicalKey(word))]?.let { return it }
        val syllables = units(word)
        coalesceSchwaBeforeHaha(syllables)
        applySchwaDeletion(syllables)
        return titleCased(render(syllables))
    }

    /** Rendered words are ASCII, so upper-casing the first char is the first Character. */
    private fun titleCased(word: String): String =
        if (word.isEmpty()) word else word.substring(0, 1).uppercase(Locale.ROOT) + word.substring(1)

    // endregion

    // region Syllable model

    /**
     * Tippi and bindi both nasalize, but they are written differently: tippi
     * is a full nasal consonant, bindi a parenthesised mark on the vowel.
     */
    private enum class Nasal { TIPPI, BINDI }

    /**
     * One orthographic syllable: an onset (possibly with subscript conjuncts),
     * a vowel (null once deleted, or for a bare consonant), and its codas.
     * (`Unit` in the Swift source; renamed to stay clear of `kotlin.Unit`.)
     */
    private class Syllable {
        var onset = ""
        var vowel: String? = null

        /** The vowel is the unwritten inherent `a`, i.e. a schwa-deletion candidate. */
        var isInherent = false

        /** The vowel came from a sihari/aunkar, i.e. a final-elision candidate. */
        var isShortMatra = false

        /**
         * The vowel came from an independent ਉ, which reads `o` (written `ou`)
         * when it opens its own syllable.
         */
        var isIndependentU = false
        var nasal: Nasal? = null

        /**
         * The onset was joined by a subscript conjunct (ਗ੍ਰ), so it is a
         * consonant cluster rather than a single consonant.
         */
        var isCluster = false
        var geminate = false
        var coda = ""
    }

    private fun units(scalars: IntArray): MutableList<Syllable> {
        val units = ArrayList<Syllable>()
        var pendingGemination = false
        var pendingSubscript = false
        var index = 0

        fun lastUnit(): Syllable {
            if (units.isEmpty()) units.add(Syllable())
            return units.last()
        }

        while (index < scalars.size) {
            val value = scalars[index]
            index += 1

            when (value) {
                0x0A4D -> { // ੍ halant: the next consonant joins this onset
                    pendingSubscript = true
                    continue
                }
                0x0A71 -> { // ੱ addak
                    pendingGemination = true
                    continue
                }
                0x0A01, 0x0A02, 0x0A70 -> { // adak bindi, bindi, tippi
                    lastUnit().nasal = if (value == 0x0A70) Nasal.TIPPI else Nasal.BINDI
                    continue
                }
                0x0A03 -> { // ਃ visarga
                    lastUnit().coda = "h"
                    continue
                }
                0x0A3C, 0x0A51, 0x0A75 -> continue // stray nukta, udaat, yakash
            }

            val matra = MATRAS[value]
            if (matra != null) {
                val unit = lastUnit()
                unit.vowel = matra
                unit.isInherent = false
                unit.isShortMatra = value in SHORT_MATRAS
                continue
            }

            val independent = INDEPENDENT_VOWELS[value]
            if (independent != null) {
                units.add(
                    Syllable().apply {
                        vowel = independent
                        isIndependentU = value == 0x0A09 // ਉ
                    },
                )
                continue
            }

            var roman = CONSONANTS[value]
            if (index < scalars.size && scalars[index] == 0x0A3C) {
                roman = NUKTA_CONSONANTS[value] ?: roman
                index += 1
            }
            val consonant = roman ?: continue

            if (pendingSubscript && units.isNotEmpty()) {
                pendingSubscript = false
                pendingGemination = false
                val host = units.last()
                // ੜ੍ਹ / ਲ੍ਹ etc: don't write the h twice.
                if (!(consonant == "h" && host.onset.endsWith("h"))) {
                    host.onset += consonant
                }
                host.isCluster = true
                continue
            }

            pendingSubscript = false
            units.add(
                Syllable().apply {
                    onset = consonant
                    vowel = "a"
                    isInherent = true
                    geminate = pendingGemination && value !in NON_GEMINATING
                },
            )
            pendingGemination = false
        }

        return units
    }

    // endregion

    // region ਹ coalescence

    /**
     * A **word-final** ਹ + sihari coalesces with the inherent schwa before it
     * into one syllable `eh`, with the ਹ in the coda: `ਪ·ਹਿ` → `peh`.
     *
     * Two conditions, both necessary:
     *
     * - **Word-final.** Only a word that *ends* in ਹਿ coalesces: `ਪਹਿ` → `Peh`,
     *   `ਮਹਿ` → `Meh`. Mid-word ਹਿ is left to the ordinary rules, so `ਸਹਿਤ` →
     *   `Sahit` and `ਪਹਿਲਾ` → `Pahilaa`.
     * - **An inherent schwa.** A written vowel before ਹ keeps its own syllable,
     *   so `ਸਾਹਿਬ` stays `Saahib` and `ਬੋਹਿਥ` stays `Bohith`.
     */
    private fun coalesceSchwaBeforeHaha(units: MutableList<Syllable>) {
        if (units.size <= 1) return
        val last = units[units.size - 1]
        val previous = units[units.size - 2]
        val coalesces = last.onset == "h" &&
            last.vowel == "i" && last.isShortMatra &&
            last.nasal == null && last.coda.isEmpty() &&
            !last.geminate && !last.isCluster &&
            previous.isInherent && previous.vowel == "a" &&
            previous.onset.isNotEmpty() &&
            previous.nasal == null && previous.coda.isEmpty()
        if (!coalesces) return
        previous.vowel = "e"
        previous.isInherent = false
        previous.coda = "h"
        units.removeAt(units.size - 1)
    }

    // endregion

    // region Schwa deletion

    private fun applySchwaDeletion(units: List<Syllable>) {
        if (units.size <= 1) return
        val last = units[units.size - 1]

        // 1. A word-final sihari/aunkar is silent — but not on ਹ, which never
        //    hosts a deleted vowel (ਕਹੁ → "Kahu", not "Kah").
        if (last.isShortMatra && last.nasal == null && last.onset.isNotEmpty() && last.onset != "h") {
            last.vowel = null
        }
        // 2. A word-final inherent schwa is deleted.
        if (last.isInherent && last.nasal == null && last.onset.isNotEmpty()) {
            last.vowel = null
        }
        // 3. Medial schwa deletion, right to left: V C ə C V → V C C V.
        if (units.size <= 2) return
        for (index in units.size - 2 downTo 1) {
            val unit = units[index]
            val deletable = unit.isInherent &&
                unit.vowel != null &&
                unit.nasal == null &&
                unit.onset.isNotEmpty() &&
                units[index - 1].vowel != null &&
                units[index + 1].vowel != null &&
                // Never delete before a vowel-initial syllable: that would
                // merge two syllables (ਭਗਉਤੀ → "Bhaguti", not "Bhagauti").
                units[index + 1].onset.isNotEmpty() &&
                // Nor before a conjunct cluster, which would pile up three
                // consonants (ਸਮਗ੍ਰੀ → "Samgri", not "Samagri").
                !units[index + 1].isCluster
            if (deletable) unit.vowel = null
        }
    }

    // endregion

    // region Rendering

    private fun render(units: List<Syllable>): String {
        val result = StringBuilder()
        for ((index, unit) in units.withIndex()) {
            var onset = unit.onset
            if (unit.geminate && onset.isNotEmpty()) {
                val isWordFinal = index == units.size - 1 && unit.vowel == null
                if (!isWordFinal) onset = onset.first().toString() + onset
            }
            var vowel = unit.vowel ?: ""
            if (onset.isEmpty() && index > 0) {
                val glideLetter = glide(index, units)
                result.append(glideLetter)
                // A glide means this ਉ opens its own syllable, where it reads
                // `o`: ਜੀਉ → "Jiyou", kept distinct from ਜੀਓ → "Jiyo".
                if (glideLetter.isNotEmpty() && unit.isIndependentU && vowel == "u") {
                    vowel = "ou"
                }
            }
            result.append(onset)
            result.append(vowel)
            when (unit.nasal) {
                Nasal.TIPPI -> result.append(tippi(index, units))
                Nasal.BINDI -> result.append("(n)")
                null -> {}
            }
            result.append(unit.coda)
        }
        return result.toString()
    }

    /**
     * `ਧਿਆਨ` → `dhiyaan`, `ਸੁਆਸ` → `suwaas`: a vowel-initial syllable after an
     * `i`/`u` takes the matching glide.
     */
    private fun glide(index: Int, units: List<Syllable>): String {
        val previous = units[index - 1]
        if (previous.nasal != null) return ""
        val vowel = previous.vowel ?: return ""
        if (vowel.endsWith("i")) return "y"
        if (vowel.endsWith("u")) return "w"
        return ""
    }

    /** Tippi is a nasal consonant, so it assimilates to what follows. */
    private fun tippi(index: Int, units: List<Syllable>): String {
        val nextOnset = if (index + 1 < units.size) units[index + 1].onset else ""
        val next = nextOnset.firstOrNull() ?: return "n"
        val letter = if (next in LABIALS) 'm' else 'n'
        // Homorganic nasal + identical consonant is written once: ਅੰਮ੍ਰਿਤ → amrit.
        return if (next == letter) "" else letter.toString()
    }

    // endregion

    // region Tables

    private val ASCII_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

    /** First Roman letters of the labial consonants ਪ ਫ ਬ ਭ ਮ and ਫ਼. */
    private val LABIALS = setOf('p', 'b', 'm', 'f')
    private val SHORT_MATRAS = setOf(0x0A3F, 0x0A41)

    /** Aspirates (plus ਸ਼/ਙ/ਞ) are never doubled by addak: `Sikhi`, not `Sikkhi`. */
    private val NON_GEMINATING = setOf(
        0x0A16, 0x0A18, 0x0A19, 0x0A1B, 0x0A1D, 0x0A1E, 0x0A20, 0x0A22,
        0x0A25, 0x0A27, 0x0A2B, 0x0A2D, 0x0A36, 0x0A39, 0x0A59, 0x0A5A, 0x0A5E,
    )

    private val CONSONANTS = mapOf(
        0x0A15 to "k", 0x0A16 to "kh", 0x0A17 to "g", 0x0A18 to "gh", 0x0A19 to "ng",
        0x0A1A to "ch", 0x0A1B to "chh", 0x0A1C to "j", 0x0A1D to "jh", 0x0A1E to "nj",
        0x0A1F to "t", 0x0A20 to "th", 0x0A21 to "d", 0x0A22 to "dh", 0x0A23 to "n",
        0x0A24 to "t", 0x0A25 to "th", 0x0A26 to "d", 0x0A27 to "dh", 0x0A28 to "n",
        0x0A2A to "p", 0x0A2B to "ph", 0x0A2C to "b", 0x0A2D to "bh", 0x0A2E to "m",
        0x0A2F to "y", 0x0A30 to "r", 0x0A32 to "l", 0x0A33 to "l", 0x0A35 to "v",
        0x0A36 to "sh", 0x0A38 to "s", 0x0A39 to "h",
        0x0A59 to "kh", 0x0A5A to "gh", 0x0A5B to "z", 0x0A5C to "rh", 0x0A5E to "f",
    )

    /**
     * Base letter + U+0A3C NUKTA, the decomposed spelling of the pairin-bindi
     * letters (which is what the bundled JSON uses).
     */
    private val NUKTA_CONSONANTS = mapOf(
        0x0A15 to "k", // ਕ਼ qaf
        0x0A16 to "kh", // ਖ਼
        0x0A17 to "gh", // ਗ਼
        0x0A1C to "z", // ਜ਼
        0x0A2B to "f", // ਫ਼
        0x0A32 to "l", // ਲ਼
        0x0A38 to "sh", // ਸ਼
    )

    /** Base letter → precomposed letter, for canonical lexicon keys. */
    private val NUKTA_COMPOSITION = mapOf(
        0x0A16 to 0x0A59, 0x0A17 to 0x0A5A, 0x0A1C to 0x0A5B,
        0x0A2B to 0x0A5E, 0x0A32 to 0x0A33, 0x0A38 to 0x0A36,
    )

    private val INDEPENDENT_VOWELS = mapOf(
        0x0A05 to "a", 0x0A06 to "aa", 0x0A07 to "i", 0x0A08 to "i", 0x0A09 to "u",
        0x0A0A to "u", 0x0A0F to "e", 0x0A10 to "ai", 0x0A13 to "o", 0x0A14 to "au",
        0x0A72 to "i", // ੲ bearer, normally overwritten by its matra
        0x0A73 to "o", // ੳ bearer, normally overwritten by its matra
    )

    private val MATRAS = mapOf(
        0x0A3E to "aa", 0x0A3F to "i", 0x0A40 to "i", 0x0A41 to "u", 0x0A42 to "u",
        0x0A47 to "e", 0x0A48 to "ai", 0x0A4B to "o", 0x0A4C to "au",
    )

    /**
     * High-frequency words where the rules read wrong, plus a few pinned words
     * the rules already get right. Small and deliberate — not a dictionary.
     */
    private val lexicon: Map<String, String> = linkedMapOf(
        "ਵਾਹਿਗੁਰੂ" to "Waheguru", // rules: Vaahiguru
        "ਵਾਹਗੁਰੂ" to "Waheguru", // rules: Vaahguru
        "ਸਤਿਗੁਰੂ" to "Satguru", // rules: Satiguru
        "ਸਤਿਗੁਰ" to "Satgur", // rules: Satigur
        "ਗੁਰੂ" to "Guru", // pinned
        "ਜੀ" to "Ji", // pinned
        "ਸ੍ਰੀ" to "Sri", // pinned
        "ਸਿੰਘ" to "Singh", // pinned
        "ਖਾਲਸਾ" to "Khalsa", // rules: Khaalsaa
        "ਖ਼ਾਲਸਾ" to "Khalsa", // ਖ਼ਾਲਸਾ; rules: Khaalsaa
        "ਅਰਦਾਸ" to "Ardaas", // pinned
        "ਨੂੰ" to "Noon", // rules: Nun
        "ਫਤਹ" to "Fateh", // rules: Phatah
        "ਫ਼ਤਹ" to "Fateh", // ਫ਼ਤਹ; rules: Fatah
        "ਫਤਹਿ" to "Fateh", // rules: Phateh
        "ਫ਼ਤਹਿ" to "Fateh", // ਫ਼ਤਹਿ; pinned, the ਹ rules now derive it
        "ਫਤਿਹ" to "Fateh", // rules: Phatih
        "ਫ਼ਤਿਹ" to "Fateh", // ਫ਼ਤਿਹ; rules: Fatih
        "ਫਤੇ" to "Fateh", // rules: Phate (the Buddha Dal spelling)
        "ਫ਼ਤੇ" to "Fateh", // ਫ਼ਤੇ; rules: Fate
        // Lexicalized spellings specified by Sarbloc. The ਹ rule is word-final
        // only, so the rules give "Rahit" — right for the tatsama ਸਹਿਤ →
        // "Sahit", wrong for these words, which are read /rɛhət/, /rɛhraas/,
        // /sɛhəj/. Which mid-word ਹਿ reduces is lexical, not predictable from
        // the spelling (ਰਹਿਤ is spelled exactly like ਸਹਿਤ), so the table is
        // the only place these can live.
        "ਰਹਿਤ" to "Rehat", // rules: Rahit
        "ਰਹਿਰਾਸ" to "Reharaas", // rules: Rahiraas
        "ਸਹਿਜ" to "Sehaj", // rules: Sahij
        // Two adjacent deletable schwas: only the rightmost goes and the left
        // is then blocked, so the rules strand the wrong one (same shape as
        // ਗੁਰਦਵਾਰਿਆਂ → "Guradvaariyaa(n)").
        "ਸੁਖਮਨੀ" to "Sukhmani", // rules: Sukhamni
    ).entries.associate { (spelling, roman) ->
        lexiconKey(canonicalKey(spelling.codePoints().toArray())) to roman
    }

    // endregion

    // region Scalar classification

    /**
     * The canonical spelling of a word: base + nukta folded to the precomposed
     * letter, so `ਖ਼ਾਲਸਾ` matches whichever way it was typed.
     */
    private fun canonicalKey(scalars: IntArray): String {
        val key = StringBuilder()
        var index = 0
        while (index < scalars.size) {
            val scalar = scalars[index]
            index += 1
            val composed = NUKTA_COMPOSITION[scalar]
            if (index < scalars.size && scalars[index] == 0x0A3C && composed != null) {
                key.appendCodePoint(composed)
                index += 1
            } else {
                key.appendCodePoint(scalar)
            }
        }
        return key.toString()
    }

    /**
     * Swift's `Dictionary<String, _>` matches keys by canonical equivalence,
     * not code-unit equality — and the precomposed nukta letters are
     * composition exclusions, so NFC itself decomposes them. Normalizing both
     * the table and the lookup to NFD reproduces that lookup exactly
     * (including nukta/halant mark reordering, which canonicalKey alone
     * does not cover).
     */
    private fun lexiconKey(key: String): String = Normalizer.normalize(key, Normalizer.Form.NFD)

    /**
     * Scalars that belong to a Gurmukhi word. Digits, ੴ and the dandas are
     * deliberately excluded: they are standalone tokens, not word material.
     */
    private fun isWordScalar(scalar: Int): Boolean = when (scalar) {
        in 0x0A01..0x0A03, // bindi, tippi signs
        in 0x0A05..0x0A0A, in 0x0A0F..0x0A10, in 0x0A13..0x0A14, // independent vowels
        in 0x0A15..0x0A28, in 0x0A2A..0x0A30, in 0x0A32..0x0A33, // consonants
        in 0x0A35..0x0A36, in 0x0A38..0x0A39,
        0x0A3C, // nukta
        in 0x0A3E..0x0A42, in 0x0A47..0x0A48, in 0x0A4B..0x0A4D, // matras, halant
        0x0A51, // udaat
        in 0x0A59..0x0A5C, 0x0A5E, // pairin-bindi letters, ੜ
        in 0x0A70..0x0A73, // tippi, addak, bearers
        0x0A75, // yakash
        -> true
        else -> false
    }

    // endregion

    // region Swift Character semantics

    /**
     * Swift's `output.last?.isWhitespace`: `last` is the final *grapheme
     * cluster*, and `Character.isWhitespace` tests that cluster's first scalar
     * against the Unicode White_Space property. Checking the last UTF-16 unit
     * would disagree on e.g. a space followed by a combining mark, which Swift
     * treats as one whitespace Character.
     *
     * The cluster's first unit stands in for its first scalar: every
     * whitespace scalar is in the BMP, so a leading surrogate (a non-BMP
     * scalar) is correctly never whitespace.
     */
    private fun lastCharacterIsWhitespace(text: CharSequence): Boolean {
        val string = text.toString()
        val clusters = BreakIterator.getCharacterInstance(Locale.ROOT)
        clusters.setText(string)
        val start = clusters.preceding(string.length)
        if (start == BreakIterator.DONE) return false
        return string[start].isSwiftWhitespace()
    }

    /**
     * Swift's `Character(scalar).isLetter || .isNumber`: `isLetter` is the
     * Unicode Alphabetic property and `isNumber` is "has a numeric type",
     * i.e. general category Nd/Nl/No (numeric Han ideographs are Alphabetic
     * already).
     */
    private fun isLetterOrNumber(scalar: Int): Boolean {
        if (Character.isAlphabetic(scalar)) return true
        return when (Character.getType(scalar).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }
    }

    // endregion
}
