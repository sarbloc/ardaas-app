import Foundation

/// Deterministic, rule-based Gurmukhi → Roman transliteration.
///
/// Pure, synchronous and stateless: the same input always produces the same
/// output, with no I/O, no model and no dependencies. It exists for two jobs:
///
/// 1. romanizing a user-authored benti, which is free Punjabi text; and
/// 2. generating a transliteration layer for variants that ship without one
///    (e.g. the Buddha Dal text).
///
/// ## Scheme
///
/// The target is the plain-ASCII, "Gurbani-style" romanization already used by
/// the bundled SGPC layer — `Waheguru Ji Ki Fateh ||`, not ISO 15919's
/// `vāhigurū jī kī fatahi`. No diacritics, no retroflex/dental distinction, one
/// Title-Cased word per Gurmukhi word.
///
/// The bundled SGPC transliteration is human-authored and internally
/// inconsistent (`ਸਭ ਥਾਂਈ` appears as both "Sabh Thaai" and "Sabh Thai"), so it
/// is a style reference, **not** a fitting target. This engine aims at the same
/// house style while being internally consistent.
///
/// ### Consonants
///
/// `ਕ k · ਖ kh · ਗ g · ਘ gh · ਙ ng · ਚ ch · ਛ chh · ਜ j · ਝ jh · ਞ nj ·`
/// `ਟ t · ਠ th · ਡ d · ਢ dh · ਣ n · ਤ t · ਥ th · ਦ d · ਧ dh · ਨ n ·`
/// `ਪ p · ਫ ph · ਬ b · ਭ bh · ਮ m · ਯ y · ਰ r · ਲ l · ਵ v · ੜ rh`
///
/// Pairin-bindi (nukta) letters: `ਸ਼ sh · ਖ਼ kh · ਗ਼ gh · ਜ਼ z · ਫ਼ f · ਲ਼ l`.
/// Both the precomposed letters (U+0A36, U+0A59…U+0A5E) and the decomposed
/// base + U+0A3C NUKTA spellings used in the bundled JSON are accepted.
///
/// Retroflex and dental series collapse (`ਟ` and `ਤ` are both `t`) because the
/// house style is plain ASCII.
///
/// ### Vowels
///
/// `ਅ a · ਆ/ਾ aa · ਇ/ਿ i · ਈ/ੀ i · ਉ/ੁ u · ਊ/ੂ u · ਏ/ੇ e · ਐ/ੈ ai ·`
/// `ਓ/ੋ o · ਔ/ੌ au`. The bearers ੳ ਅ ੲ carry whichever matra follows them; a
/// bare ੳ with no matra is `o`.
///
/// Only the `a`/`aa` length contrast is written. `i`/`u` length is not, which
/// is what the house style does in its most common forms: `Ji`, `Ki`, `Sri`,
/// `Guru` — not `Jee`, `Kee`, `Sree`, `Guroo`.
///
/// A glide is inserted before a vowel-initial syllable that follows an `i` or
/// `u`: `ਧਿਆਨ` → `Dhiyaan`, `ਸੁਆਸ` → `Suwaas`.
///
/// An independent ਉ that opens its own syllable this way — i.e. one that takes
/// a glide — is read `o` rather than `u`, and is written `ou` so that it stays
/// distinct from ਓ: `ਜੀਉ` → `Jiyou`, where `ਜੀਓ` → `Jiyo`. Elsewhere ਉ is the
/// second half of a plain vowel juncture (`ਨਉ` → `Nau`, `ਨਾਉ` → `Naau`,
/// `ਭਗਉਤੀ` → `Bhagauti`) or a word-initial `u` (`ਉਪਦੇਸ਼` → `Updesh`), and those
/// keep `u`.
///
/// ### Nasalization, gemination, conjuncts
///
/// Tippi (ੰ) is a full nasal consonant: `n`, or `m` before a labial
/// (`ਪ ਫ ਬ ਭ ਮ ਫ਼`) — `ਪੰਥ` → `Panth`, `ਸਿੰਘ` → `Singh`, `ਅੰਮ੍ਰਿਤਸਰ` →
/// `Amritsar`. When the nasal letter equals the following consonant it is
/// written once rather than doubled (`ਮੰਨ` → `Man`, not `Mann`).
///
/// Bindi (ਂ, and the rare adak bindi ਁ) marks a *nasalized vowel* rather than a
/// nasal consonant, so it is written parenthesised and never assimilates:
/// `ਸਿੰਘਾਂ` → `Singhaa(n)`, `ਲਈਂ` → `Lai(n)`, `ਥਾਂਈ` → `Thaa(n)i`. The
/// parentheses are what keep the two signs apart in Roman — `Singh` is the
/// name, `Singhaa(n)` its oblique plural.
///
/// Addak (ੱ) geminates the following consonant by doubling its first Roman
/// letter (`ਦਿੱਤੇ` → `Ditte`, `ਸੱਚੇ` → `Sacche`), with two readability
/// exceptions: aspirates are not doubled (`ਸਿੱਖੀ` → `Sikhi`, not `Sikkhi`) and
/// a gemination that would land word-final after schwa deletion is written
/// single (`ਸਰਬੱਤ` → `Sarbat`, `ਸਿੱਖ` → `Sikh`).
///
/// Subscript conjuncts (੍ + ਹ/ਰ/ਵ, i.e. pairin haha/rara/wawa) join the onset:
/// `ਪ੍ਰ` → `pr`, `ਕ੍ਰਿ` → `kri`, `ਤਿਨ੍ਹਾਂ` → `Tinhaan`. A pairin haha after a
/// consonant whose Roman already ends in `h` is absorbed (`ਚੜ੍ਹਦੀ` →
/// `Charhdi`, not `Charhhdi`).
///
/// ### ਹ and the vowel it carries
///
/// ਹ is weak: it never hosts a deleted vowel, and it colours a preceding
/// inherent schwa. Two consequences, both applied before schwa deletion:
///
/// - **A schwa before a word-final ਹ + sihari coalesces into `eh`.** A word
///   ending in ਹਿ is read /-ɛh/, not /-əhi/, so the schwa becomes `e` and the
///   ਹ closes the syllable: `ਪਹਿ` → `Peh`, `ਮਹਿ` → `Meh`, `ਕਹਿ` → `Keh`.
///   Two things narrow it. It is **word-final only** — mid-word ਹਿ takes the
///   ordinary rules, so `ਸਹਿਤ` → `Sahit` and `ਪਹਿਲਾ` → `Pahilaa` — and the
///   trigger is specifically the inherent schwa, so a written vowel before ਹ
///   is unaffected and `ਸਾਹਿਬ` stays `Saahib`, `ਬੋਹਿਥ` stays `Bohith`.
///   (`ਵਾਹਿਗੁਰੂ` → `Waheguru` shows the same `e` after a long vowel, but that
///   is a lexicalized reduction, so it lives in the lexicon below rather than
///   in this rule.)
/// - **A short vowel on ਹ is never elided** by rule 1 below: `ਕਹੁ` → `Kahu`,
///   not `Kah`.
///
/// Restricting the first rule to word-final position is what keeps the tatsama
/// borrowings that hold their /əhi/ intact: `ਸਹਿਤ` reads `Sahit`. The words
/// that *are* reduced mid-word are lexical, not predictable from the spelling,
/// so they go in the lexicon: `ਰਹਿਤ` → `Rehat`, `ਰਹਿਰਾਸ` → `Reharaas`,
/// `ਸਹਿਜ` → `Sehaj`.
///
/// ### Schwa deletion
///
/// The single biggest quality lever. Three rules, applied per word:
///
/// 1. **Final short-vowel elision.** A word-final sihari (ਿ) or aunkar (ੁ) is
///    silent, the standard Gurbani reading convention:
///    `ਸਿਮਰਿ` → `Simar`, `ਸਤਿ` → `Sat`, `ਅਮਰਦਾਸੁ` → `Amardaas`. Not on ਹ,
///    per the section above.
/// 2. **Final schwa deletion.** The inherent `a` of a word-final consonant is
///    dropped: `ਨਾਨਕ` → `Naanak`, not `Naanaka`.
/// 3. **Medial schwa deletion.** Scanning right to left, the inherent `a` of a
///    non-initial, non-final syllable is dropped when both neighbours still
///    have a pronounced vowel (the classic `VCəCV → VCCV` rule):
///    `ਪਾਤਸ਼ਾਹੀ` → `Paatshaahi`, `ਹਰਗੋਬਿੰਦ` → `Hargobind`, `ਅਰਦਾਸ` → `Ardaas`.
///    The first syllable of a word is never emptied, a syllable carrying a
///    nasal keeps its vowel, nothing is deleted before a vowel-initial
///    syllable, where it would merge two syllables (`ਭਗਉਤੀ` → `Bhagauti`, not
///    `Bhaguti`), and nothing is deleted before a conjunct cluster, where it
///    would pile up three consonants (`ਸਮਗ੍ਰੀ` → `Samagri`, not `Samgri`).
///
/// Known limitations of the rule: it is orthographic, so it cannot know about
/// morpheme boundaries (compounds may keep or lose a schwa a reader would
/// place differently), it does not restore the schwa that Punjabi keeps before
/// certain sonorant clusters, and rule 1 will wrongly silence a final short
/// vowel in the rare modern word that genuinely ends in one. Where two
/// deletable schwas are adjacent only the rightmost goes, so `ਗੁਰਦਵਾਰਿਆਂ`
/// comes out `Guradvaariyaa(n)`.
///
/// A vowel-vowel juncture with no glide is written as-is, so `ਲਈਂ` → `Lai(n)`
/// can be misread as the `ai` of ਐ. Writing it `La-i(n)` would be no clearer,
/// and the house style itself writes such junctures plain ("Nau", "Bhagauti").
///
/// ### Everything else
///
/// `ੴ` → `Ik-Onkar`; `॥` → `||` and `।` → `|`. These are standalone tokens:
/// each is separated from its neighbours by one space, even when the source
/// runs them into an adjacent word or Latin text (`ਜੀ॥ਸਤਿ` → `Ji || Sat`,
/// `ਜੀ॥hello` → `Ji || hello`), while an existing space is never doubled and
/// following punctuation is left alone (`ਜੀ॥.` → `Ji ||.`).
/// Gurmukhi digits ੦–੯ → ASCII `0`–`9`.
/// Line breaks, spacing and any Latin/ASCII already in the input pass through
/// untouched and un-recased, so a mixed-script benti survives intact.
/// Udaat (ੑ) and yakash (ੵ) are dropped; visarga (ਃ) renders as `h`.
///
/// ### Exception lexicon
///
/// A deliberately small table of high-frequency words whose rule output reads
/// wrong (`ਵਾਹਿਗੁਰੂ` → `Waheguru`, not `Vaahiguru`). It is a quality patch, not
/// a dictionary: see `lexicon` below. Words already produced correctly by the
/// rules (`ਸਿੰਘ`, `ਅਰਦਾਸ`) are pinned there too, so that a later rule change
/// cannot silently alter them.
enum GurmukhiTransliterator {

    // MARK: - Public API

    /// Transliterates Gurmukhi text to Roman, leaving non-Gurmukhi input as-is.
    static func transliterate(_ gurmukhi: String) -> String {
        var output = ""
        var word: [Unicode.Scalar] = []
        // ੴ and the dandas are standalone tokens: they are set off by a space
        // even when the source runs them into the next word ("ਜੀ॥ਸਤਿ").
        var afterStandaloneToken = false

        func separate() {
            if afterStandaloneToken, let last = output.last, !last.isWhitespace {
                output.append(" ")
            }
        }

        func flushWord() {
            guard !word.isEmpty else { return }
            separate()
            output += transliterate(word: word)
            word.removeAll(keepingCapacity: true)
            afterStandaloneToken = false
        }

        for scalar in gurmukhi.unicodeScalars {
            if isWordScalar(scalar) {
                word.append(scalar)
                continue
            }
            flushWord()

            switch scalar.value {
            case 0x0A74: // ੴ
                separate()
                output += "Ik-Onkar"
                afterStandaloneToken = true
            case 0x0964, 0x0965: // । ॥
                if let last = output.last, !last.isWhitespace {
                    output.append(" ")
                }
                output += scalar.value == 0x0965 ? "||" : "|"
                afterStandaloneToken = true
            case 0x0A66...0x0A6F: // Gurmukhi digits
                separate()
                output.append(asciiDigits[Int(scalar.value - 0x0A66)])
                afterStandaloneToken = false
            default:
                // Latin/ASCII running straight into a standalone token
                // ("ਜੀ॥hello") is separated too, but punctuation is left
                // alone so "ਜੀ॥." doesn't become "Ji ||.".
                if afterStandaloneToken, Character(scalar).isLetter || Character(scalar).isNumber {
                    separate()
                }
                output.unicodeScalars.append(scalar)
                afterStandaloneToken = false
            }
        }
        flushWord()
        return output
    }

    // MARK: - Word assembly

    private static func transliterate(word: [Unicode.Scalar]) -> String {
        if let known = lexicon[canonicalKey(word)] {
            return known
        }
        var syllables = units(from: word)
        coalesceSchwaBeforeHaha(&syllables)
        applySchwaDeletion(&syllables)
        return titleCased(render(syllables))
    }

    private static func titleCased(_ word: String) -> String {
        guard let first = word.first else { return word }
        return first.uppercased() + word.dropFirst()
    }

    // MARK: - Syllable model

    /// Tippi and bindi both nasalize, but they are written differently: tippi
    /// is a full nasal consonant, bindi a parenthesised mark on the vowel.
    private enum Nasal {
        case tippi
        case bindi
    }

    /// One orthographic syllable: an onset (possibly with subscript conjuncts),
    /// a vowel (`nil` once deleted, or for a bare consonant), and its codas.
    private struct Unit {
        var onset = ""
        var vowel: String? = nil
        /// The vowel is the unwritten inherent `a`, i.e. a schwa-deletion candidate.
        var isInherent = false
        /// The vowel came from a sihari/aunkar, i.e. a final-elision candidate.
        var isShortMatra = false
        /// The vowel came from an independent ਉ, which reads `o` (written `ou`)
        /// when it opens its own syllable.
        var isIndependentU = false
        var nasal: Nasal? = nil
        /// The onset was joined by a subscript conjunct (ਗ੍ਰ), so it is a
        /// consonant cluster rather than a single consonant.
        var isCluster = false
        var geminate = false
        var coda = ""
    }

    private static func units(from scalars: [Unicode.Scalar]) -> [Unit] {
        var units: [Unit] = []
        var pendingGemination = false
        var pendingSubscript = false
        var index = 0

        while index < scalars.count {
            let value = scalars[index].value
            index += 1

            switch value {
            case 0x0A4D: // ੍ halant: the next consonant joins this onset
                pendingSubscript = true
                continue
            case 0x0A71: // ੱ addak
                pendingGemination = true
                continue
            case 0x0A01, 0x0A02, 0x0A70: // adak bindi, bindi, tippi
                if units.isEmpty { units.append(Unit()) }
                units[units.count - 1].nasal = value == 0x0A70 ? .tippi : .bindi
                continue
            case 0x0A03: // ਃ visarga
                if units.isEmpty { units.append(Unit()) }
                units[units.count - 1].coda = "h"
                continue
            case 0x0A3C, 0x0A51, 0x0A75: // stray nukta, udaat, yakash
                continue
            default:
                break
            }

            if let matra = matras[value] {
                if units.isEmpty { units.append(Unit()) }
                units[units.count - 1].vowel = matra
                units[units.count - 1].isInherent = false
                units[units.count - 1].isShortMatra = shortMatras.contains(value)
                continue
            }

            if let vowel = independentVowels[value] {
                var unit = Unit()
                unit.vowel = vowel
                unit.isIndependentU = value == 0x0A09 // ਉ
                units.append(unit)
                continue
            }

            var roman = consonants[value]
            if index < scalars.count, scalars[index].value == 0x0A3C {
                roman = nuktaConsonants[value] ?? roman
                index += 1
            }
            guard let consonant = roman else { continue }

            if pendingSubscript, !units.isEmpty {
                pendingSubscript = false
                pendingGemination = false
                // ੜ੍ਹ / ਲ੍ਹ etc: don't write the h twice.
                if !(consonant == "h" && units[units.count - 1].onset.hasSuffix("h")) {
                    units[units.count - 1].onset += consonant
                }
                units[units.count - 1].isCluster = true
                continue
            }

            pendingSubscript = false
            var unit = Unit()
            unit.onset = consonant
            unit.vowel = "a"
            unit.isInherent = true
            unit.geminate = pendingGemination && !nonGeminating.contains(value)
            pendingGemination = false
            units.append(unit)
        }

        return units
    }

    // MARK: - ਹ coalescence

    /// A **word-final** ਹ + sihari coalesces with the inherent schwa before it
    /// into one syllable `eh`, with the ਹ in the coda: `ਪ·ਹਿ` → `peh`.
    ///
    /// Two conditions, both necessary:
    ///
    /// - **Word-final.** Only a word that *ends* in ਹਿ coalesces: `ਪਹਿ` → `Peh`,
    ///   `ਮਹਿ` → `Meh`. Mid-word ਹਿ is left to the ordinary rules, so `ਸਹਿਤ` →
    ///   `Sahit` and `ਪਹਿਲਾ` → `Pahilaa`.
    /// - **An inherent schwa.** A written vowel before ਹ keeps its own syllable,
    ///   so `ਸਾਹਿਬ` stays `Saahib` and `ਬੋਹਿਥ` stays `Bohith`.
    private static func coalesceSchwaBeforeHaha(_ units: inout [Unit]) {
        guard units.count > 1 else { return }
        let last = units.count - 1
        guard units[last].onset == "h",
              units[last].vowel == "i", units[last].isShortMatra,
              units[last].nasal == nil, units[last].coda.isEmpty,
              !units[last].geminate, !units[last].isCluster,
              units[last - 1].isInherent, units[last - 1].vowel == "a",
              !units[last - 1].onset.isEmpty,
              units[last - 1].nasal == nil, units[last - 1].coda.isEmpty
        else { return }
        units[last - 1].vowel = "e"
        units[last - 1].isInherent = false
        units[last - 1].coda = "h"
        units.removeLast()
    }

    // MARK: - Schwa deletion

    private static func applySchwaDeletion(_ units: inout [Unit]) {
        guard units.count > 1 else { return }
        let last = units.count - 1

        // 1. A word-final sihari/aunkar is silent — but not on ਹ, which never
        //    hosts a deleted vowel (ਕਹੁ → "Kahu", not "Kah").
        if units[last].isShortMatra, units[last].nasal == nil,
           !units[last].onset.isEmpty, units[last].onset != "h" {
            units[last].vowel = nil
        }
        // 2. A word-final inherent schwa is deleted.
        if units[last].isInherent, units[last].nasal == nil, !units[last].onset.isEmpty {
            units[last].vowel = nil
        }
        // 3. Medial schwa deletion, right to left: V C ə C V → V C C V.
        guard units.count > 2 else { return }
        for index in stride(from: units.count - 2, through: 1, by: -1) {
            guard units[index].isInherent,
                  units[index].vowel != nil,
                  units[index].nasal == nil,
                  !units[index].onset.isEmpty,
                  units[index - 1].vowel != nil,
                  units[index + 1].vowel != nil,
                  // Never delete before a vowel-initial syllable: that would
                  // merge two syllables (ਭਗਉਤੀ → "Bhaguti", not "Bhagauti").
                  !units[index + 1].onset.isEmpty,
                  // Nor before a conjunct cluster, which would pile up three
                  // consonants (ਸਮਗ੍ਰੀ → "Samgri", not "Samagri").
                  !units[index + 1].isCluster
            else { continue }
            units[index].vowel = nil
        }
    }

    // MARK: - Rendering

    private static func render(_ units: [Unit]) -> String {
        var result = ""
        for (index, unit) in units.enumerated() {
            var onset = unit.onset
            if unit.geminate, let first = onset.first {
                let isWordFinal = index == units.count - 1 && unit.vowel == nil
                if !isWordFinal { onset = String(first) + onset }
            }
            var vowel = unit.vowel ?? ""
            if onset.isEmpty, index > 0 {
                let glideLetter = glide(before: index, in: units)
                result += glideLetter
                // A glide means this ਉ opens its own syllable, where it reads
                // `o`: ਜੀਉ → "Jiyou", kept distinct from ਜੀਓ → "Jiyo".
                if !glideLetter.isEmpty, unit.isIndependentU, vowel == "u" {
                    vowel = "ou"
                }
            }
            result += onset
            result += vowel
            if let nasal = unit.nasal {
                switch nasal {
                case .tippi: result += tippi(at: index, in: units)
                case .bindi: result += "(n)"
                }
            }
            result += unit.coda
        }
        return result
    }

    /// `ਧਿਆਨ` → `dhiyaan`, `ਸੁਆਸ` → `suwaas`: a vowel-initial syllable after an
    /// `i`/`u` takes the matching glide.
    private static func glide(before index: Int, in units: [Unit]) -> String {
        let previous = units[index - 1]
        guard previous.nasal == nil, let vowel = previous.vowel else { return "" }
        if vowel.hasSuffix("i") { return "y" }
        if vowel.hasSuffix("u") { return "w" }
        return ""
    }

    /// Tippi is a nasal consonant, so it assimilates to what follows.
    private static func tippi(at index: Int, in units: [Unit]) -> String {
        let nextOnset = index + 1 < units.count ? units[index + 1].onset : ""
        guard let next = nextOnset.first else { return "n" }
        let letter: Character = labials.contains(next) ? "m" : "n"
        // Homorganic nasal + identical consonant is written once: ਅੰਮ੍ਰਿਤ → amrit.
        return next == letter ? "" : String(letter)
    }

    // MARK: - Tables

    private static let asciiDigits: [Character] = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9"]
    /// First Roman letters of the labial consonants ਪ ਫ ਬ ਭ ਮ and ਫ਼.
    private static let labials: Set<Character> = ["p", "b", "m", "f"]
    private static let shortMatras: Set<UInt32> = [0x0A3F, 0x0A41]

    /// Aspirates (plus ਸ਼/ਙ/ਞ) are never doubled by addak: `Sikhi`, not `Sikkhi`.
    private static let nonGeminating: Set<UInt32> = [
        0x0A16, 0x0A18, 0x0A19, 0x0A1B, 0x0A1D, 0x0A1E, 0x0A20, 0x0A22,
        0x0A25, 0x0A27, 0x0A2B, 0x0A2D, 0x0A36, 0x0A39, 0x0A59, 0x0A5A, 0x0A5E,
    ]

    private static let consonants: [UInt32: String] = [
        0x0A15: "k", 0x0A16: "kh", 0x0A17: "g", 0x0A18: "gh", 0x0A19: "ng",
        0x0A1A: "ch", 0x0A1B: "chh", 0x0A1C: "j", 0x0A1D: "jh", 0x0A1E: "nj",
        0x0A1F: "t", 0x0A20: "th", 0x0A21: "d", 0x0A22: "dh", 0x0A23: "n",
        0x0A24: "t", 0x0A25: "th", 0x0A26: "d", 0x0A27: "dh", 0x0A28: "n",
        0x0A2A: "p", 0x0A2B: "ph", 0x0A2C: "b", 0x0A2D: "bh", 0x0A2E: "m",
        0x0A2F: "y", 0x0A30: "r", 0x0A32: "l", 0x0A33: "l", 0x0A35: "v",
        0x0A36: "sh", 0x0A38: "s", 0x0A39: "h",
        0x0A59: "kh", 0x0A5A: "gh", 0x0A5B: "z", 0x0A5C: "rh", 0x0A5E: "f",
    ]

    /// Base letter + U+0A3C NUKTA, the decomposed spelling of the pairin-bindi
    /// letters (which is what the bundled JSON uses).
    private static let nuktaConsonants: [UInt32: String] = [
        0x0A15: "k",  // ਕ਼ qaf
        0x0A16: "kh", // ਖ਼
        0x0A17: "gh", // ਗ਼
        0x0A1C: "z",  // ਜ਼
        0x0A2B: "f",  // ਫ਼
        0x0A32: "l",  // ਲ਼
        0x0A38: "sh", // ਸ਼
    ]

    /// Base letter → precomposed letter, for canonical lexicon keys.
    private static let nuktaComposition: [UInt32: UInt32] = [
        0x0A16: 0x0A59, 0x0A17: 0x0A5A, 0x0A1C: 0x0A5B,
        0x0A2B: 0x0A5E, 0x0A32: 0x0A33, 0x0A38: 0x0A36,
    ]

    private static let independentVowels: [UInt32: String] = [
        0x0A05: "a", 0x0A06: "aa", 0x0A07: "i", 0x0A08: "i", 0x0A09: "u",
        0x0A0A: "u", 0x0A0F: "e", 0x0A10: "ai", 0x0A13: "o", 0x0A14: "au",
        0x0A72: "i", // ੲ bearer, normally overwritten by its matra
        0x0A73: "o", // ੳ bearer, normally overwritten by its matra
    ]

    private static let matras: [UInt32: String] = [
        0x0A3E: "aa", 0x0A3F: "i", 0x0A40: "i", 0x0A41: "u", 0x0A42: "u",
        0x0A47: "e", 0x0A48: "ai", 0x0A4B: "o", 0x0A4C: "au",
    ]

    /// High-frequency words where the rules read wrong, plus a few pinned words
    /// the rules already get right. Small and deliberate — not a dictionary.
    private static let lexicon: [String: String] = [
        "ਵਾਹਿਗੁਰੂ": "Waheguru",  // rules: Vaahiguru
        "ਵਾਹਗੁਰੂ": "Waheguru",   // rules: Vaahguru
        "ਸਤਿਗੁਰੂ": "Satguru",    // rules: Satiguru
        "ਸਤਿਗੁਰ": "Satgur",      // rules: Satigur
        "ਗੁਰੂ": "Guru",          // pinned
        "ਜੀ": "Ji",              // pinned
        "ਸ੍ਰੀ": "Sri",           // pinned
        "ਸਿੰਘ": "Singh",         // pinned
        "ਖਾਲਸਾ": "Khalsa",       // rules: Khaalsaa
        "ਖ਼ਾਲਸਾ": "Khalsa",       // rules: Khaalsaa
        "ਅਰਦਾਸ": "Ardaas",       // pinned
        "ਨੂੰ": "Noon",            // rules: Nun
        "ਫਤਹ": "Fateh",          // rules: Phatah
        "ਫ਼ਤਹ": "Fateh",          // rules: Fatah
        "ਫਤਹਿ": "Fateh",         // rules: Phateh
        "ਫ਼ਤਹਿ": "Fateh",         // pinned; the ਹ rules now derive it
        "ਫਤਿਹ": "Fateh",         // rules: Phatih
        "ਫ਼ਤਿਹ": "Fateh",         // rules: Fatih
        "ਫਤੇ": "Fateh",          // rules: Phate (the Buddha Dal spelling)
        "ਫ਼ਤੇ": "Fateh",          // rules: Fate
        // Lexicalized spellings specified by Sarbloc. The ਹ rule is word-final
        // only, so the rules give "Rahit" — right for the tatsama ਸਹਿਤ →
        // "Sahit", wrong for these words, which are read /rɛhət/, /rɛhraas/,
        // /sɛhəj/. Which mid-word ਹਿ reduces is lexical, not predictable from
        // the spelling (ਰਹਿਤ is spelled exactly like ਸਹਿਤ), so the table is
        // the only place these can live.
        "ਰਹਿਤ": "Rehat",         // rules: Rahit
        "ਰਹਿਰਾਸ": "Reharaas",    // rules: Rahiraas
        "ਸਹਿਜ": "Sehaj",         // rules: Sahij
        // Two adjacent deletable schwas: only the rightmost goes and the left
        // is then blocked, so the rules strand the wrong one (same shape as
        // ਗੁਰਦਵਾਰਿਆਂ → "Guradvaariyaa(n)").
        "ਸੁਖਮਨੀ": "Sukhmani",    // rules: Sukhamni
    ].reduce(into: [String: String]()) { table, entry in
        table[canonicalKey(Array(entry.key.unicodeScalars))] = entry.value
    }

    // MARK: - Scalar classification

    /// The canonical spelling of a word: base + nukta folded to the precomposed
    /// letter, so `ਖ਼ਾਲਸਾ` matches whichever way it was typed.
    private static func canonicalKey(_ scalars: [Unicode.Scalar]) -> String {
        var key = String.UnicodeScalarView()
        var index = 0
        while index < scalars.count {
            let scalar = scalars[index]
            index += 1
            if index < scalars.count, scalars[index].value == 0x0A3C,
               let composed = nuktaComposition[scalar.value],
               let replacement = Unicode.Scalar(composed) {
                key.append(replacement)
                index += 1
            } else {
                key.append(scalar)
            }
        }
        return String(key)
    }

    /// Scalars that belong to a Gurmukhi word. Digits, ੴ and the dandas are
    /// deliberately excluded: they are standalone tokens, not word material.
    private static func isWordScalar(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x0A01...0x0A03, // bindi, tippi signs
             0x0A05...0x0A0A, 0x0A0F...0x0A10, 0x0A13...0x0A14, // independent vowels
             0x0A15...0x0A28, 0x0A2A...0x0A30, 0x0A32...0x0A33, // consonants
             0x0A35...0x0A36, 0x0A38...0x0A39,
             0x0A3C, // nukta
             0x0A3E...0x0A42, 0x0A47...0x0A48, 0x0A4B...0x0A4D, // matras, halant
             0x0A51, // udaat
             0x0A59...0x0A5C, 0x0A5E, // pairin-bindi letters, ੜ
             0x0A70...0x0A73, // tippi, addak, bearers
             0x0A75: // yakash
            return true
        default:
            return false
        }
    }
}
