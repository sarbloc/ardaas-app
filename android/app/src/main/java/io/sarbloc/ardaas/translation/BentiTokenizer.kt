package io.sarbloc.ardaas.translation

import io.sarbloc.ardaas.translation.IcuRegexClasses.SPACE
import java.io.File
import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Port of Ardaas/Translation/BentiTokenizer.swift. Both platforms assert the
// shared ArdaasTests/Fixtures/tokenizer_parity.json id-for-id.
//
// Implemented directly from `tokenizer_src.json` (HF *tokenizers* fast
// format), per spec section 3:
//
//   1. Added tokens (38 language tags + 4 specials) are matched first, as
//      standalone words; `rstrip: true` consumes their trailing whitespace.
//   2. Remaining text: NFKC normalize, collapse 2+ spaces.
//   3. Metaspace pre-tokenization: split on spaces, prepend U+2581 to every
//      word (`prepend_scheme: "always"`).
//   4. BPE over the 33,888-piece vocab + 53,746 merges (no byte fallback,
//      no dropout, `fuse_unk: false` — each unknown code point becomes <unk>).
//   5. Append `</s>` (id 2).
//   6. Remap any id >= 32322 (`src_dict_size`) to <unk>=3 — the fast vocab
//      carries merge-intermediate pieces the model does not know.

/**
 * Source-side tokenizer: a tagged, preprocessed benti in, encoder-ready ids
 * out. Immutable once loaded, so one instance can serve every translation.
 *
 * @throws TranslationError.MalformedModelFile if [tokenizerFile] is not a
 *   tokenizer of the expected shape. I/O failures reading it propagate as
 *   [java.io.IOException], as `Data(contentsOf:)` does on iOS; callers check
 *   the file exists first and surface anything else through
 *   [TranslationError.wrap].
 */
class BentiTokenizer private constructor(private val tables: Tables) {

    constructor(tokenizerFile: File) : this(Tables.load(tokenizerFile))

    /**
     * Encode a tagged, preprocessed string (`"eng_Latn pan_Guru <tokens>"`)
     * into encoder-ready ids: `[4, 46, <pieces...>, 2]`.
     */
    fun encode(tagged: String): LongArray {
        val ids = ArrayList<Long>()
        var cursor = 0
        // 1. Split out added tokens.
        for (match in tables.addedTokenMatcher.findAll(tagged)) {
            if (match.range.first > cursor) {
                encodeSegment(tagged.substring(cursor, match.range.first), ids)
            }
            // Membership is guaranteed — the matcher was built from the keys.
            val token = match.groupValues[1]
            ids += tables.addedTokens[token] ?: UNK_ID
            cursor = match.range.last + 1
        }
        if (cursor < tagged.length) encodeSegment(tagged.substring(cursor), ids)

        // 5. Post-processor appends </s>.
        ids += EOS_ID

        // 6. Extended-vocab remap.
        return LongArray(ids.size) { ids[it].takeIf { id -> id < SRC_DICT_SIZE } ?: UNK_ID }
    }

    private fun encodeSegment(segment: String, into: MutableList<Long>) {
        // 2. NFKC, then collapse runs of 2+ spaces (Replace normalizer).
        val text = MULTI_SPACE.replace(Normalizer.normalize(segment, Normalizer.Form.NFKC), " ")

        // 3. Metaspace: split on U+0020, prepend U+2581 to every word.
        for (word in text.split(' ')) {
            if (word.isNotEmpty()) bpe(METASPACE + word, into)
        }
    }

    private fun bpe(word: String, into: MutableList<Long>) {
        val symbols = word.codePoints().toArray().mapTo(ArrayList()) { String(Character.toChars(it)) }
        while (symbols.size >= 2) {
            val best = lowestRankedPair(symbols) ?: break
            symbols[best] = symbols[best] + symbols[best + 1]
            symbols.removeAt(best + 1)
        }
        // fuse_unk: false — every out-of-vocab symbol is its own <unk>.
        symbols.mapTo(into) { tables.vocab[it] ?: UNK_ID }
    }

    /** Index of the left symbol of the adjacent pair with the lowest merge rank; the first on ties. */
    private fun lowestRankedPair(symbols: List<String>): Int? {
        var bestRank = Int.MAX_VALUE
        var bestIndex: Int? = null
        for (i in 0 until symbols.size - 1) {
            val rank = tables.mergeRanks[symbols[i] to symbols[i + 1]] ?: continue
            if (rank < bestRank) {
                bestRank = rank
                bestIndex = i
            }
        }
        return bestIndex
    }

    /** The parsed `tokenizer_src.json`, reduced to the lookups encoding needs. */
    private class Tables(
        val vocab: Map<String, Long>,
        val mergeRanks: Map<Pair<String, String>, Int>,
        /** Added-token content → id (language tags + specials). */
        val addedTokens: Map<String, Long>,
        val addedTokenMatcher: Regex,
    ) {
        companion object {
            /** Unknown keys (normalizer, decoder, …) are fixed by the spec and not read. */
            private val json = Json { ignoreUnknownKeys = true }

            fun load(file: File): Tables {
                val parsed = try {
                    json.decodeFromString<TokenizerFile>(file.readText(Charsets.UTF_8))
                } catch (error: IllegalArgumentException) {
                    // SerializationException is an IllegalArgumentException;
                    // catching the parent also covers a number that overflows.
                    throw malformed(file, "missing or mistyped model/vocab/merges/added_tokens: ${error.message}")
                }
                // An empty alternation would match the empty string everywhere.
                if (parsed.addedTokens.isEmpty()) throw malformed(file, "no added_tokens")
                val addedTokens = parsed.addedTokens.associate { it.content to it.id }
                return Tables(
                    vocab = parsed.model.vocab,
                    mergeRanks = mergeRanks(file, parsed.model.merges),
                    addedTokens = addedTokens,
                    addedTokenMatcher = addedTokenMatcher(addedTokens.keys),
                )
            }

            private fun mergeRanks(file: File, merges: List<List<String>>): Map<Pair<String, String>, Int> {
                val ranks = HashMap<Pair<String, String>, Int>(merges.size * 2)
                merges.forEachIndexed { rank, merge ->
                    if (merge.size != 2) throw malformed(file, "merge entry with ${merge.size} parts")
                    ranks[merge[0] to merge[1]] = rank
                }
                return ranks
            }

            /**
             * Standalone-word matcher for added tokens (`single_word: true`),
             * with `rstrip: true` consuming trailing whitespace. The 4 specials
             * (`<s>` etc.) are folded into the same matcher — they never occur
             * in benti input (the preprocessor pads `<` and `>`), and matching
             * them as words is harmless here. Longest first, so no token is
             * shadowed by a prefix of itself.
             */
            private fun addedTokenMatcher(tokens: Set<String>): Regex {
                val alternation = tokens
                    .sortedByDescending { it.length }
                    .joinToString("|") { Regex.escape(it) }
                return Regex("""(?:^|(?<=[$SPACE]))($alternation)(?=[$SPACE]|$)[$SPACE]*""")
            }

            private fun malformed(file: File, detail: String) =
                TranslationError.MalformedModelFile(name = file.name, detail = detail)
        }
    }

    @Serializable
    private class TokenizerFile(
        @SerialName("added_tokens") val addedTokens: List<AddedToken>,
        val model: BpeModel,
    )

    @Serializable
    private class AddedToken(val id: Long, val content: String)

    @Serializable
    private class BpeModel(val vocab: Map<String, Long>, val merges: List<List<String>>)

    companion object {
        const val BOS_ID: Long = 0
        const val PAD_ID: Long = 1
        const val EOS_ID: Long = 2
        const val UNK_ID: Long = 3

        /**
         * `tokenizer_meta.json: src_dict_size` — ids at or above this are
         * unknown to the model and must be remapped to [UNK_ID].
         */
        const val SRC_DICT_SIZE: Long = 32322

        /**
         * Encoder positional limit. One benti is one model call — there is no
         * sentence splitting — so anything longer has to be refused rather
         * than silently truncated into a mistranslation.
         */
        const val MAX_SOURCE_TOKENS: Int = 256

        private const val METASPACE = "▁"
        private val MULTI_SPACE = Regex(" {2,}")

        /**
         * Pure guard, so the refusal is testable without the 359 MB artifact.
         *
         * @throws TranslationError.InputTooLong past [MAX_SOURCE_TOKENS].
         */
        fun validateSourceLength(ids: LongArray) {
            if (ids.size > MAX_SOURCE_TOKENS) {
                throw TranslationError.InputTooLong(tokens = ids.size, limit = MAX_SOURCE_TOKENS)
            }
        }
    }
}
