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
/// `ਓ/ੋ o · ਔ/ੌ au`. The bearers ੳ ਅ ੲ carry whichever matra follows them.
///
/// Only the `a`/`aa` length contrast is written. `i`/`u` length is not, which
/// is what the house style does in its most common forms: `Ji`, `Ki`, `Sri`,
/// `Guru` — not `Jee`, `Kee`, `Sree`, `Guroo`.
///
/// A glide is inserted before a vowel-initial syllable that follows an `i` or
/// `u`: `ਧਿਆਨ` → `Dhiyaan`, `ਸੁਆਸ` → `Suwaas`.
///
/// ### Nasalization, gemination, conjuncts
///
/// Tippi (ੰ) and bindi (ਂ) both render as `n`, or `m` before a labial
/// (`ਪ ਫ ਬ ਭ ਮ`): `ਪੰਥ` → `Panth`, `ਅੰਮ੍ਰਿਤਸਰ` → `Amritsar`. When the nasal
/// letter equals the following consonant it is written once rather than
/// doubled (`ਮੰਨ` → `Man`, not `Mann`).
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
/// ### Schwa deletion
///
/// The single biggest quality lever. Three rules, applied per word:
///
/// 1. **Final short-vowel elision.** A word-final sihari (ਿ) or aunkar (ੁ) is
///    silent, the standard Gurbani reading convention:
///    `ਸਿਮਰਿ` → `Simar`, `ਸਤਿ` → `Sat`, `ਅਮਰਦਾਸੁ` → `Amardaas`.
/// 2. **Final schwa deletion.** The inherent `a` of a word-final consonant is
///    dropped: `ਨਾਨਕ` → `Naanak`, not `Naanaka`.
/// 3. **Medial schwa deletion.** Scanning right to left, the inherent `a` of a
///    non-initial, non-final syllable is dropped when both neighbours still
///    have a pronounced vowel (the classic `VCəCV → VCCV` rule):
///    `ਪਾਤਸ਼ਾਹੀ` → `Paatshaahi`, `ਹਰਗੋਬਿੰਦ` → `Hargobind`, `ਅਰਦਾਸ` → `Ardaas`.
///    The first syllable of a word is never emptied, and a syllable carrying a
///    nasal keeps its vowel.
///
/// Known limitations of the rule: it is orthographic, so it cannot know about
/// morpheme boundaries (compounds may keep or lose a schwa a reader would
/// place differently), it does not restore the schwa that Punjabi keeps before
/// certain sonorant clusters, and rule 1 will wrongly silence a final short
/// vowel in the rare modern word that genuinely ends in one.
///
/// ### Everything else
///
/// `ੴ` → `Ik-Onkar`; `॥` → `||` and `।` → `|`, each separated from the
/// preceding word by one space; Gurmukhi digits ੦–੯ → ASCII `0`–`9`.
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
        for scalar in gurmukhi.unicodeScalars {
            if isWordScalar(scalar) {
                word.append(scalar)
                continue
            }
            flush(&word, into: &output)
            append(passthrough: scalar, to: &output)
        }
        flush(&word, into: &output)
        return output
    }

    // MARK: - Word assembly

    private static func flush(_ word: inout [Unicode.Scalar], into output: inout String) {
        guard !word.isEmpty else { return }
        output += transliterate(word: word)
        word.removeAll(keepingCapacity: true)
    }

    private static func append(passthrough scalar: Unicode.Scalar, to output: inout String) {
        switch scalar.value {
        case 0x0A74: // ੴ
            output += "Ik-Onkar"
        case 0x0965: // ॥
            appendDanda("||", to: &output)
        case 0x0964: // ।
            appendDanda("|", to: &output)
        case 0x0A66...0x0A6F: // Gurmukhi digits
            output.append(asciiDigits[Int(scalar.value - 0x0A66)])
        default:
            output.unicodeScalars.append(scalar)
        }
    }

    /// Dandas are set off from the preceding word by exactly one space, which
    /// is what the house style does ("Waheguru Ji Ki Fateh ||").
    private static func appendDanda(_ danda: String, to output: inout String) {
        if let last = output.last, !last.isWhitespace {
            output.append(" ")
        }
        output += danda
    }

    private static func transliterate(word: [Unicode.Scalar]) -> String {
        if let known = lexicon[canonicalKey(word)] {
            return known
        }
        var syllables = units(from: word)
        applySchwaDeletion(&syllables)
        return titleCased(render(syllables))
    }

    private static func titleCased(_ word: String) -> String {
        guard let first = word.first else { return word }
        return first.uppercased() + word.dropFirst()
    }

    // MARK: - Syllable model

    /// One orthographic syllable: an onset (possibly with subscript conjuncts),
    /// a vowel (`nil` once deleted, or for a bare consonant), and its codas.
    private struct Unit {
        var onset = ""
        var vowel: String? = nil
        /// The vowel is the unwritten inherent `a`, i.e. a schwa-deletion candidate.
        var isInherent = false
        /// The vowel came from a sihari/aunkar, i.e. a final-elision candidate.
        var isShortMatra = false
        var hasNasal = false
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
                units[units.count - 1].hasNasal = true
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

    // MARK: - Schwa deletion

    private static func applySchwaDeletion(_ units: inout [Unit]) {
        guard units.count > 1 else { return }
        let last = units.count - 1

        // 1. A word-final sihari/aunkar is silent.
        if units[last].isShortMatra, !units[last].hasNasal, !units[last].onset.isEmpty {
            units[last].vowel = nil
        }
        // 2. A word-final inherent schwa is deleted.
        if units[last].isInherent, !units[last].hasNasal, !units[last].onset.isEmpty {
            units[last].vowel = nil
        }
        // 3. Medial schwa deletion, right to left: V C ə C V → V C C V.
        guard units.count > 2 else { return }
        for index in stride(from: units.count - 2, through: 1, by: -1) {
            guard units[index].isInherent,
                  units[index].vowel != nil,
                  !units[index].hasNasal,
                  !units[index].onset.isEmpty,
                  units[index - 1].vowel != nil,
                  units[index + 1].vowel != nil
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
            if onset.isEmpty, index > 0 {
                result += glide(before: index, in: units)
            }
            result += onset
            result += unit.vowel ?? ""
            if unit.hasNasal { result += nasal(at: index, in: units) }
            result += unit.coda
        }
        return result
    }

    /// `ਧਿਆਨ` → `dhiyaan`, `ਸੁਆਸ` → `suwaas`: a vowel-initial syllable after an
    /// `i`/`u` takes the matching glide.
    private static func glide(before index: Int, in units: [Unit]) -> String {
        let previous = units[index - 1]
        guard !previous.hasNasal, let vowel = previous.vowel else { return "" }
        if vowel.hasSuffix("i") { return "y" }
        if vowel.hasSuffix("u") { return "w" }
        return ""
    }

    private static func nasal(at index: Int, in units: [Unit]) -> String {
        let nextOnset = index + 1 < units.count ? units[index + 1].onset : ""
        guard let next = nextOnset.first else { return "n" }
        let letter: Character = labials.contains(next) ? "m" : "n"
        // Homorganic nasal + identical consonant is written once: ਅੰਮ੍ਰਿਤ → amrit.
        return next == letter ? "" : String(letter)
    }

    // MARK: - Tables

    private static let asciiDigits: [Character] = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9"]
    private static let labials: Set<Character> = ["p", "b", "m"]
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
        0x0A73: "u", // ੳ bearer, normally overwritten by its matra
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
        "ਫਤਹਿ": "Fateh",         // rules: Phatah
        "ਫ਼ਤਹਿ": "Fateh",         // rules: Fatah
        "ਫਤਿਹ": "Fateh",         // rules: Phatih
        "ਫ਼ਤਿਹ": "Fateh",         // rules: Fatih
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
