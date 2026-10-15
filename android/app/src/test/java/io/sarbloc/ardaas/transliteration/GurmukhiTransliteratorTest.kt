package io.sarbloc.ardaas.transliteration

import io.sarbloc.ardaas.content.BundledAssets
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of ArdaasTests/GurmukhiTransliteratorTests.swift, case for case.
 *
 * Hand-curated fixtures for the transliteration scheme documented on
 * [GurmukhiTransliterator].
 *
 * These are **not** fitted to the bundled SGPC transliteration: that layer is
 * human-authored and internally inconsistent (`ਸਭ ਥਾਂਈ` appears as both "Sabh
 * Thaai" and "Sabh Thai"), so it cannot be reproduced by rules. Where this
 * engine legitimately differs from it, the expectation below is *this
 * engine's* output and the divergence is called out in a comment.
 */
class GurmukhiTransliteratorTest {

    private fun expect(vararg cases: Pair<String, String>) {
        for ((input, expected) in cases) {
            assertEquals("input: $input", expected, GurmukhiTransliterator.transliterate(input))
        }
    }

    // region Consonants

    /**
     * Every consonant alone. A one-syllable word keeps its inherent vowel —
     * schwa deletion would leave it with no vowel at all.
     */
    @Test
    fun consonantsInIsolation() {
        expect(
            "ਕ" to "Ka", "ਖ" to "Kha", "ਗ" to "Ga", "ਘ" to "Gha", "ਙ" to "Nga",
            "ਚ" to "Cha", "ਛ" to "Chha", "ਜ" to "Ja", "ਝ" to "Jha", "ਞ" to "Nja",
            "ਟ" to "Ta", "ਠ" to "Tha", "ਡ" to "Da", "ਢ" to "Dha", "ਣ" to "Na",
            "ਤ" to "Ta", "ਥ" to "Tha", "ਦ" to "Da", "ਧ" to "Dha", "ਨ" to "Na",
            "ਪ" to "Pa", "ਫ" to "Pha", "ਬ" to "Ba", "ਭ" to "Bha", "ਮ" to "Ma",
            "ਯ" to "Ya", "ਰ" to "Ra", "ਲ" to "La", "ਵ" to "Va", "ੜ" to "Rha",
            "ਸ" to "Sa", "ਹ" to "Ha",
        )
    }

    /** The same consonants in a simple open syllable (C + kanna). */
    @Test
    fun consonantsInSimpleSyllable() {
        expect(
            "ਕਾ" to "Kaa", "ਖਾ" to "Khaa", "ਗਾ" to "Gaa", "ਘਾ" to "Ghaa",
            "ਙਾ" to "Ngaa", "ਚਾ" to "Chaa", "ਛਾ" to "Chhaa", "ਜਾ" to "Jaa",
            "ਝਾ" to "Jhaa", "ਞਾ" to "Njaa", "ਟਾ" to "Taa", "ਠਾ" to "Thaa",
            "ਡਾ" to "Daa", "ਢਾ" to "Dhaa", "ਣਾ" to "Naa", "ਤਾ" to "Taa",
            "ਥਾ" to "Thaa", "ਦਾ" to "Daa", "ਧਾ" to "Dhaa", "ਨਾ" to "Naa",
            "ਪਾ" to "Paa", "ਫਾ" to "Phaa", "ਬਾ" to "Baa", "ਭਾ" to "Bhaa",
            "ਮਾ" to "Maa", "ਯਾ" to "Yaa", "ਰਾ" to "Raa", "ਲਾ" to "Laa",
            "ਵਾ" to "Vaa", "ੜਾ" to "Rhaa", "ਸਾ" to "Saa", "ਹਾ" to "Haa",
        )
    }

    /** Pairin-bindi letters, precomposed (U+0A36, U+0A59…U+0A5E). */
    @Test
    fun pairinBindiConsonantsPrecomposed() {
        expect(
            "ਸ਼" to "Sha", "ਖ਼" to "Kha", "ਗ਼" to "Gha",
            "ਜ਼" to "Za", "ਫ਼" to "Fa", "ਲ਼" to "La",
            "ਸ਼ਾ" to "Shaa", "ਜ਼ਾ" to "Zaa",
            "ਫ਼ਾ" to "Faa",
        )
    }

    /**
     * The same letters spelled base + U+0A3C NUKTA, which is how the bundled
     * JSON stores them. Both spellings must transliterate identically.
     */
    @Test
    fun pairinBindiConsonantsDecomposed() {
        expect(
            "ਸ਼" to "Sha", "ਖ਼" to "Kha",
            "ਗ਼" to "Gha", "ਜ਼" to "Za",
            "ਫ਼" to "Fa", "ਲ਼" to "La",
        )
        assertEquals(
            GurmukhiTransliterator.transliterate("ਸ਼ਬਦ"),
            GurmukhiTransliterator.transliterate("ਸ਼ਬਦ"),
        )
    }

    // endregion

    // region Vowels

    @Test
    fun independentVowels() {
        expect(
            "ਅ" to "A", "ਆ" to "Aa", "ਇ" to "I", "ਈ" to "I", "ਉ" to "U",
            "ਊ" to "U", "ਏ" to "E", "ਐ" to "Ai", "ਓ" to "O", "ਔ" to "Au",
        )
    }

    /**
     * Every dependent vowel sign on a single consonant. Vowel length is
     * written for a/aa only: ਿ and ੀ both give `i`, ੁ and ੂ both give `u`,
     * which is what the house style does (Ji, Ki, Sri, Guru).
     */
    @Test
    fun dependentVowelSigns() {
        expect(
            "ਕ" to "Ka", "ਕਾ" to "Kaa", "ਕਿ" to "Ki", "ਕੀ" to "Ki",
            "ਕੁ" to "Ku", "ਕੂ" to "Ku", "ਕੇ" to "Ke", "ਕੈ" to "Kai",
            "ਕੋ" to "Ko", "ਕੌ" to "Kau",
        )
    }

    /** The bearers ੳ ਅ ੲ take whichever matra follows them; a bare ੳ is `o`. */
    @Test
    fun vowelBearersTakeTheirMatra() {
        expect(
            "ੳੂ" to "U", // ੳ + dulainkar
            "ੲੀ" to "I", // ੲ + bihari
            "ਅਾ" to "Aa", // ਅ + kanna
            "ੳ" to "O", // bare ੳ, carrying no matra
        )
    }

    /** A vowel-initial syllable after an i/u takes the matching glide. */
    @Test
    fun glideBeforeVowelInitialSyllable() {
        expect(
            "ਧਿਆਨ" to "Dhiyaan",
            "ਪਿਆਰੇ" to "Piyaare",
            "ਸੁਆਸ" to "Suwaas",
            "ਜੀਓ" to "Jiyo",
            "ਹੋਇ" to "Hoi", // no glide after o
            "ਭਾਈ" to "Bhaai", // no glide after aa
        )
    }

    /**
     * An independent ਉ that opens its own syllable — i.e. one that takes a
     * glide — reads `o`, written `ou` to stay distinct from ਓ. Everywhere
     * else ਉ is a plain `u`.
     */
    @Test
    fun independentOoraOpeningItsOwnSyllable() {
        expect(
            "ਜੀਉ" to "Jiyou",
            "ਜੀਓ" to "Jiyo", // ਓ stays a bare "o": the two don't collide
            "ਪੀਉ" to "Piyou",
            // No glide, so no own syllable: these are plain vowel junctures.
            "ਨਉ" to "Nau",
            "ਨਾਉ" to "Naau",
            "ਭਗਉਤੀ" to "Bhagauti",
            "ਉਪਦੇਸ਼" to "Updesh", // word-initial ਉ
            "ਊਚੇ" to "Uche", // ਊ is unaffected
        )
    }

    // endregion

    // region Nasalization, gemination, conjuncts

    /** Tippi is a nasal *consonant*, so it is written plainly and assimilates. */
    @Test
    fun tippiNasalization() {
        expect(
            "ਪੰਥ" to "Panth",
            "ਅੰਗ" to "Ang",
            "ਸਿੰਘ" to "Singh",
            "ਪਿੰਡੁ" to "Pind",
            "ਗੋਬਿੰਦ" to "Gobind",
            "ਪੰਪ" to "Pamp", // → m before a labial
            "ਸੰਬ" to "Samb",
            "ਸੰਫ਼" to "Samf", // ਫ਼ counts as labial
            "ਸੰਫ਼" to "Samf", // …either spelling
            "ਅੰਮ੍ਰਿਤ" to "Amrit", // homorganic m + ਮ written once
            "ਅੰਮ੍ਰਿਤਸਰ" to "Amritsar",
            "ਮੰਨ" to "Man", // homorganic n + ਨ written once
        )
    }

    /**
     * Bindi marks a nasalized *vowel*, so it is parenthesised and never
     * assimilates. Only bindi is bracketed — tippi above stays plain.
     */
    @Test
    fun bindiIsParenthesised() {
        expect(
            "ਮਾਂ" to "Maa(n)",
            "ਨਹੀਂ" to "Nahi(n)",
            "ਦਸਾਂ" to "Dasaa(n)",
            "ਲਈਂ" to "Lai(n)",
            "ਥਾਂਈ" to "Thaa(n)i", // bindi mid-word, on the kanna
            "ਧੌਂਸਿਆਂ" to "Dhau(n)siyaa(n)",
            // A word carrying both: the tippi is the ng of "Singh", the bindi
            // the nasalized plural ending.
            "ਸਿੰਘਾਂ" to "Singhaa(n)",
            // No m-assimilation for bindi, unlike tippi ("Pamp" above).
            "ਮਾਂਪ" to "Maa(n)p",
            // The rare adak bindi (U+0A01) is a bindi too.
            "ਮਾਁ" to "Maa(n)",
        )
    }

    @Test
    fun addakGemination() {
        expect(
            "ਦਿੱਤੇ" to "Ditte",
            "ਸੱਚੇ" to "Sacche",
            "ਸਰਬੱਤ" to "Sarbat", // gemination lands word-final → written single
            "ਅਟੱਲ" to "Atal", // same; the SGPC layer has "Attal"
            "ਸਿੱਖ" to "Sikh",
            "ਸਿੱਖੀ" to "Sikhi", // aspirates are not doubled ("Sikkhi")
            "ਸੁੱਖ" to "Sukh",
        )
    }

    @Test
    fun subscriptConjuncts() {
        expect(
            "ਪ੍ਰਿਥਮ" to "Pritham", // pairin rara
            "ਕ੍ਰਿਪਾ" to "Kripaa",
            "ਪ੍ਰਭੂ" to "Prabhu",
            "ਤਿਨ੍ਹਾਂ" to "Tinhaa(n)", // pairin haha
            "ਖੁਲ੍ਹੇ" to "Khulhe",
            "ਸ੍ਵਾਸ" to "Svaas", // pairin wawa
            "ਚੜ੍ਹਦੀ" to "Charhdi", // ੜ is "rh"; the pairin haha is absorbed
        )
    }

    // endregion

    // region Schwa deletion

    @Test
    fun wordFinalSchwaDeletion() {
        expect(
            "ਨਾਨਕ" to "Naanak",
            "ਭਗਤ" to "Bhagat",
            "ਧਰਮ" to "Dharam",
            "ਅਕਾਲ" to "Akaal",
            "ਸ਼ਬਦ" to "Shabad",
        )
    }

    @Test
    fun medialSchwaDeletion() {
        expect(
            "ਅਰਦਾਸ" to "Ardaas", // a·ra·daa·s → ardaas
            "ਪਾਤਸ਼ਾਹੀ" to "Paatshaahi", // SGPC layer: "Paatshaahee"
            "ਹਰਗੋਬਿੰਦ" to "Hargobind",
            "ਜ਼ੋਰਾਵਰ" to "Zoraavar",
            "ਪੰਜਾਬੀ" to "Panjaabi",
            // Never deleted before a vowel-initial syllable: dropping this
            // schwa would merge two syllables into one ("Bhaguti").
            "ਭਗਉਤੀ" to "Bhagauti",
            // Nor before a conjunct cluster, which would pile up three
            // consonants ("Samgri").
            "ਸਮਗ੍ਰੀ" to "Samagri",
            // The block is on the *following* syllable only, so a conjunct
            // earlier in the word still lets a later schwa go.
            "ਅੰਮ੍ਰਿਤਸਰ" to "Amritsar",
        )
    }

    /**
     * A word-final sihari/aunkar is silent — the standard Gurbani reading
     * convention, and what the SGPC layer does ("Simar Kai", not "Simari").
     */
    @Test
    fun wordFinalShortVowelElision() {
        expect(
            "ਸਿਮਰਿ" to "Simar",
            "ਸਤਿ" to "Sat",
            "ਹਰਿ" to "Har",
            "ਨਿਧਿ" to "Nidh",
            "ਨਾਮੁ" to "Naam",
            "ਅਮਰਦਾਸੁ" to "Amardaas",
            "ਕਿ" to "Ki", // single syllable: the vowel is all it has
            "ਸੂਤ੍ਰਿ" to "Sutr", // …a conjunct onset elides its sihari too
        )
    }

    /**
     * ਹ never hosts a deleted vowel, and a schwa before a **word-final**
     * ਹ + sihari coalesces with it into `eh`.
     */
    @Test
    fun hahaKeepsItsVowel() {
        expect(
            // Word-final ਹਿ: the schwa before it becomes `e` and the ਹ closes
            // the syllable.
            "ਪਹਿ" to "Peh",
            "ਮਹਿ" to "Meh",
            "ਕਹਿ" to "Keh",
            "ਰਹਿ" to "Reh",
            "ਸਹਿ" to "Seh",
            "ਕਰਹਿ" to "Kareh", // …also when the word is longer
            "ਤਿਸਹਿ" to "Tiseh",
            "ਕਹੁ" to "Kahu", // a final aunkar on ਹ is not elided either
            "ਬਹੁ" to "Bahu",
            // Only the *inherent* schwa coalesces: a written vowel before ਹ
            // keeps its own syllable, word-final or not.
            "ਸਾਹਿਬ" to "Saahib",
            "ਬੋਹਿਥ" to "Bohith",
            "ਨਾਹਿ" to "Naahi",
            "ਹਿੰਮਤ" to "Himat", // word-initial ਹਿ has no schwa to absorb
        )
    }

    /**
     * The rule is word-final only, so mid-word ਹਿ takes the ordinary rules.
     * That is what keeps the tatsama borrowings that hold their /əhi/ intact;
     * the words genuinely reduced mid-word are lexical, not predictable from
     * the spelling, so they are lexicon entries instead.
     */
    @Test
    fun midWordHahaIsNotCoalesced() {
        expect(
            "ਸਹਿਤ" to "Sahit",
            "ਮਹਿਮਾ" to "Mahimaa",
            // Spelled exactly like ਸਹਿਤ but read /rɛhət/, which is why it takes
            // a lexicon entry — the rules alone would give "Rahit".
            "ਰਹਿਤ" to "Rehat",
            // The cost of the narrowing, pinned so it can't change silently: a
            // word whose mid-word ਹਿ *is* reduced by readers comes out
            // unreduced until it is lexicalized. ਰਹਿਰਾਸ and ਸਹਿਜ were
            // lexicalized in #69 because they reach bundled content; this one
            // is not in the canonical text or the slot list, so it only
            // reaches a user-authored benti and stays on the rules.
            "ਪਹਿਲਾ" to "Pahilaa",
        )
    }

    // endregion

    // region Exception lexicon

    @Test
    fun exceptionLexicon() {
        expect(
            "ਵਾਹਿਗੁਰੂ" to "Waheguru", // rules alone: "Vaahiguru"
            "ਵਾਹਗੁਰੂ" to "Waheguru",
            "ਸਤਿਗੁਰੂ" to "Satguru",
            "ਸਤਿਗੁਰ" to "Satgur",
            "ਗੁਰੂ" to "Guru",
            "ਜੀ" to "Ji",
            "ਸ੍ਰੀ" to "Sri",
            "ਸਿੰਘ" to "Singh",
            "ਖਾਲਸਾ" to "Khalsa", // rules alone: "Khaalsaa"
            "ਅਰਦਾਸ" to "Ardaas",
            "ਨੂੰ" to "Noon",
            "ਫਤਿਹ" to "Fateh",
            "ਫਤਹਿ" to "Fateh",
            "ਫਤੇ" to "Fateh", // the Buddha Dal spelling
            "ਰਹਿਤ" to "Rehat", // rules alone: "Rahit"
            // The three words #69 lexicalized, all of them reaching bundled
            // content through occasions.json. Spellings are Sarbloc's.
            "ਰਹਿਰਾਸ" to "Reharaas", // rules alone: "Rahiraas" (mid-word ਹਿ)
            "ਸਹਿਜ" to "Sehaj", // rules alone: "Sahij" (mid-word ਹਿ)
            "ਸੁਖਮਨੀ" to "Sukhmani", // rules alone: "Sukhamni" (adjacent schwas)
        )
        // In the phrases they actually ship in, so the entry is pinned where
        // the bundled Roman layer reads it, not just in isolation.
        expect(
            "ਰਹਿਰਾਸ ਸਾਹਿਬ ਦੇ ਪਾਠ" to "Reharaas Saahib De Paath",
            "ਸ੍ਰੀ ਸਹਿਜ ਪਾਠ ਦੇ ਭੋਗ" to "Sri Sehaj Paath De Bhog",
            "ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ" to "Sukhmani Saahib De Paath",
        )
        // The nukta spelling of ਫ਼ਤਹਿ resolves to the same entry.
        expect("ਫ਼ਤਹਿ" to "Fateh")
        // Lexicon entries only cover the listed forms; inflections fall back
        // to the rules, which is a deliberate limit on the table's size.
        expect("ਖਾਲਸੇ" to "Khaalse")
    }

    // endregion

    // region Symbols, digits, punctuation

    @Test
    fun ikOnkar() {
        expect(
            "ੴ" to "Ik-Onkar",
            "ੴ ਸਤਿ ਨਾਮੁ" to "Ik-Onkar Sat Naam",
        )
    }

    @Test
    fun dandas() {
        expect(
            "ਬੋਲੇ ਸੋ ਨਿਹਾਲ ॥ ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ ॥" to "Bole So Nihaal || Sat Sri Akaal ||",
            "ਖਾਲਸਾ ਜੀ ।" to "Khalsa Ji |",
            "ਜੀ॥" to "Ji ||", // a danda is always set off by one space
            "ਜੀ ॥" to "Ji ||", // …and never doubles an existing one
            // ੴ and the dandas are standalone tokens, so they are separated
            // from the next token even when the source runs them together.
            "ਜੀ॥ਸਤਿ" to "Ji || Sat",
            "ਜੀ।ਸਤਿ" to "Ji | Sat",
            "ਜੀ॥ ਸਤਿ" to "Ji || Sat",
            "ਜੀ॥੧੦" to "Ji || 10",
            "ੴਵਾਹਿਗੁਰੂ" to "Ik-Onkar Waheguru",
            "ਜੀ॥hello" to "Ji || hello", // …including Latin
            "ੴ2026" to "Ik-Onkar 2026",
            "ਜੀ॥." to "Ji ||.", // but not punctuation
        )
    }

    @Test
    fun gurmukhiDigits() {
        expect(
            "੦੧੨੩੪੫੬੭੮੯" to "0123456789",
            "ਪਾਤਸ਼ਾਹੀ ੧੦॥" to "Paatshaahi 10 ||",
        )
    }

    // endregion

    // region Passthrough

    @Test
    fun latinAndMixedScriptPassthrough() {
        expect(
            "ਗੁਰੂ Nanak ji" to "Guru Nanak ji", // Latin is not re-cased
            "ਵਾਹਿਗੁਰੂ (Waheguru) 2026" to "Waheguru (Waheguru) 2026",
            "Ardaas" to "Ardaas",
            "" to "",
        )
    }

    @Test
    fun whitespaceAndLineBreaksArePreserved() {
        expect(
            "   " to "   ",
            "ਜੀ\nਜੀ" to "Ji\nJi",
            "ਸਤਿ  ਸ੍ਰੀ\tਅਕਾਲ" to "Sat  Sri\tAkaal",
            "\n\nਜੀ\n" to "\n\nJi\n",
        )
    }

    // endregion

    // region Real phrases

    /**
     * Phrases from the bundled SGPC text where the house style is
     * unambiguous, plus a few where this engine deliberately differs.
     */
    @Test
    fun realPhrases() {
        expect(
            "ੴ ਵਾਹਿਗੁਰੂ ਜੀ ਕੀ ਫ਼ਤਹਿ॥" to "Ik-Onkar Waheguru Ji Ki Fateh ||",
            "ਵਾਹਿਗੁਰੂ ਜੀ ਕੀ ਫਤਿਹ॥" to "Waheguru Ji Ki Fateh ||",
            "ਪ੍ਰਿਥਮ ਭਗੌਤੀ ਸਿਮਰਿ ਕੈ" to "Pritham Bhagauti Simar Kai",
            "ਦੇਗ ਤੇਗ ਫ਼ਤਹ" to "Deg Teg Fateh",
            // The Buddha Dal heading, whose Roman layer this engine generates.
            "ੴ ਸ੍ਰੀ ਵਾਹਿਗੁਰੂ ਜੀ ਕੀ ਫਤੇ ॥" to "Ik-Onkar Sri Waheguru Ji Ki Fateh ||",
            "ਬੋਲੋ ਜੀ ਵਾਹਿਗੁਰੂ॥" to "Bolo Ji Waheguru ||",
            // SGPC layer: "Waheguru Ji Ka Khalsa" — ਕਾ is a kanna, so this
            // engine writes the long vowel: "Kaa".
            "ਵਾਹਿਗੁਰੂ ਜੀ ਕਾ ਖਾਲਸਾ॥" to "Waheguru Ji Kaa Khalsa ||",
            // SGPC layer: "Naanak Naam Chardi Kala" — ੜ੍ਹ is written "rh" and
            // final kanna as "aa", both consistently applied here.
            "ਨਾਨਕ ਨਾਮ ਚੜ੍ਹਦੀ ਕਲਾ, ਤੇਰੇ ਭਾਣੇ ਸਰਬੱਤ ਦਾ ਭਲਾ॥" to
                "Naanak Naam Charhdi Kalaa, Tere Bhaane Sarbat Daa Bhalaa ||",
            // SGPC layer: "Sabh Thaai" in one segment and "Sabh Thai" in
            // another. Neither is derivable: the bindi on ਥਾਂ is written.
            "ਸਭ ਥਾਂਈ" to "Sabh Thaa(n)i",
            // Proof-read as correct against the Buddha Dal text.
            "ਦੇਗ਼ ਤੇਗ਼" to "Degh Tegh",
            "ਤੁਮ ਪਹਿ ਅਰਦਾਸਿ" to "Tum Peh Ardaas",
            "ਕਹੁ ਨਾਨਕ" to "Kahu Naanak",
            "ਸਗਲ ਸਮਗ੍ਰੀ ਤੁਮਰੈ ਸੂਤ੍ਰਿ ਧਾਰੀ" to "Sagal Samagri Tumrai Sutr Dhaari",
            "ਜੀਉ ਪਿੰਡੁ ਸਭੁ ਤੇਰੀ ਰਾਸਿ" to "Jiyou Pind Sabh Teri Raas",
        )
    }

    @Test
    fun multiLineSegmentKeepsItsShape() {
        val input = "ਸ੍ਰੀ ਭਗੌਤੀ ਜੀ ਸਹਾਇ॥\nਵਾਰ ਸ੍ਰੀ ਭਗੌਤੀ ਜੀ ਕੀ ਪਾਤਸ਼ਾਹੀ ੧੦॥"
        assertEquals(
            "Sri Bhagauti Ji Sahaai ||\nVaar Sri Bhagauti Ji Ki Paatshaahi 10 ||",
            GurmukhiTransliterator.transliterate(input),
        )
    }

    /** A benti is free text: Gurmukhi, Latin and punctuation mixed. */
    @Test
    fun bentiStyleFreeText() {
        expect(
            "ਮੇਰਾ ਨਾਮ ਸੁੱਖ ਹੈ" to "Meraa Naam Sukh Hai",
            "ਅਰਦਾਸ ਹੈ ਜੀ — please help" to "Ardaas Hai Ji — please help",
        )
    }

    @Test
    fun transliterationIsDeterministic() {
        val input = "ੴ ਵਾਹਿਗੁਰੂ ਜੀ ਕੀ ਫ਼ਤਹਿ॥ ਸਭ ਥਾਂਈ ਹੋਇ ਸਹਾਇ॥"
        val first = GurmukhiTransliterator.transliterate(input)
        repeat(3) {
            assertEquals(first, GurmukhiTransliterator.transliterate(input))
        }
    }

    /**
     * The engine must produce a non-empty layer for every bundled segment —
     * its second job is generating the Buddha Dal variant's missing layer.
     */
    @Test
    fun everyBundledSegmentTransliterates() {
        val library = BundledAssets.library()
        for (variant in library.variants) {
            for (segment in variant.content.segments) {
                val output = GurmukhiTransliterator.transliterate(segment.gurmukhi)
                assertFalse(
                    "empty transliteration for ${variant.id}/${segment.id}",
                    output.isBlank(),
                )
                assertFalse(
                    "untransliterated Gurmukhi left in ${variant.id}/${segment.id}: $output",
                    output.codePoints().anyMatch { it in 0x0A00..0x0A7F },
                )
                assertEquals(
                    "line count changed for ${variant.id}/${segment.id}",
                    lineFeedCharacters(segment.gurmukhi),
                    lineFeedCharacters(output),
                )
            }
        }
    }

    /**
     * The Buddha Dal Roman layer is **generated** by this engine, unlike the
     * SGPC one which is human-authored. Pinning it here is what stops the
     * bundled JSON silently going stale: any rule change that would alter the
     * canonical text fails CI and forces the layer to be regenerated (and
     * re-proof-read) rather than leaving the app shipping one romanization in
     * the canonical text and a different one for the user's benti.
     *
     * Stricter than the Swift test, which compares by canonical equivalence:
     * this one requires the regenerated layer to match byte for byte.
     */
    @Test
    fun bundledBuddhaDalLayerMatchesTheEngine() {
        val content = requireNotNull(BundledAssets.library().variant("buddha-dal")) { "no buddha-dal variant" }.content
        assertFalse(content.segments.isEmpty())
        for (segment in content.segments) {
            assertEquals(
                "bundled transliteration has drifted from the engine " +
                    "for segment ${segment.id} — regenerate the layer",
                segment.transliteration,
                GurmukhiTransliterator.transliterate(segment.gurmukhi),
            )
        }
    }

    // endregion

    // region Shared parity fixture

    @Serializable
    private data class ParityFixture(val cases: List<Case>) {
        @Serializable
        data class Case(val rule: String, val gurmukhi: String, val expected: String)
    }

    private companion object {
        /** Unknown keys (the fixture's `_meta`) are skipped, as Swift's Decodable does. */
        val fixtureJson = Json { ignoreUnknownKeys = true }
    }

    /**
     * The iOS suite asserts the same file
     * (`ArdaasTests/Fixtures/transliteration-parity.json`, #84), so a rule
     * that drifts between the platforms fails a build instead of romanizing
     * the same benti two ways. Never edit an expected value to make one side
     * pass. Read from the iOS test tree — passed in by Gradle — so there is
     * one copy and nothing to keep in sync.
     */
    @Test
    fun sharedParityFixture() {
        val path = requireNotNull(System.getProperty("ardaas.transliterationParityFixture")) {
            "ardaas.transliterationParityFixture not set — run through Gradle"
        }
        val fixture = fixtureJson.decodeFromString<ParityFixture>(File(path).readText(Charsets.UTF_8))
        assertTrue("only ${fixture.cases.size} cases", fixture.cases.size >= 600)
        for (case in fixture.cases) {
            assertEquals(
                "[${case.rule}] input: ${case.gurmukhi}",
                case.expected,
                GurmukhiTransliterator.transliterate(case.gurmukhi),
            )
        }
    }

    // endregion

    /**
     * Swift's `filter { $0 == "\n" }.count` counts *Characters*, and a CRLF
     * pair is one Character that is not "\n" — so only a bare LF counts.
     */
    private fun lineFeedCharacters(text: String): Int =
        text.indices.count { text[it] == '\n' && (it == 0 || text[it - 1] != '\r') }
}
