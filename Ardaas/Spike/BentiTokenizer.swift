import Foundation

// Spike (#35): source-side tokenizer implemented directly from
// `tokenizer_src.json` (HF *tokenizers* fast format), per spec section 3:
//
//   1. Added tokens (38 language tags + 4 specials) are matched first, as
//      standalone words; `rstrip: true` consumes their trailing whitespace.
//   2. Remaining text: NFKC normalize, collapse 2+ spaces.
//   3. Metaspace pre-tokenization: split on spaces, prepend U+2581 to every
//      word (`prepend_scheme: "always"`).
//   4. BPE over the 33,888-piece vocab + 53,746 merges (no byte fallback,
//      no dropout, `fuse_unk: false` — each unknown scalar becomes <unk>).
//   5. Append `</s>` (id 2).
//   6. Remap any id >= 32322 (`src_dict_size`) to <unk>=3 — the fast vocab
//      carries merge-intermediate pieces the model does not know.
//
// Parity with the reference tokenizer is asserted id-for-id by
// `BentiTokenizerTests` against `tokenizer_parity.json`.

enum BentiTokenizerError: Error, CustomStringConvertible {
    case malformedTokenizerFile(String)

    var description: String {
        switch self {
        case .malformedTokenizerFile(let detail):
            return "Malformed tokenizer_src.json: \(detail)"
        }
    }
}

final class BentiTokenizer {
    static let bosId: Int64 = 0
    static let padId: Int64 = 1
    static let eosId: Int64 = 2
    static let unkId: Int64 = 3
    /// `tokenizer_meta.json: src_dict_size` — ids at or above this are
    /// unknown to the model and must be remapped to `unkId`.
    static let srcDictSize: Int64 = 32322

    private struct Pair: Hashable {
        let left: String
        let right: String
    }

    private let vocab: [String: Int64]
    private let mergeRanks: [Pair: Int]
    /// Added-token content -> id (language tags + specials).
    private let addedTokens: [String: Int64]
    private let addedTokenMatcher: NSRegularExpression

    init(tokenizerFile: URL) throws {
        let data = try Data(contentsOf: tokenizerFile)
        guard
            let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let model = root["model"] as? [String: Any],
            let rawVocab = model["vocab"] as? [String: Any],
            let rawMerges = model["merges"] as? [[String]],
            let rawAdded = root["added_tokens"] as? [[String: Any]]
        else {
            throw BentiTokenizerError.malformedTokenizerFile("missing model/vocab/merges/added_tokens")
        }

        var vocab = [String: Int64](minimumCapacity: rawVocab.count)
        for (piece, id) in rawVocab {
            guard let id = (id as? NSNumber)?.int64Value else {
                throw BentiTokenizerError.malformedTokenizerFile("non-numeric id for piece \(piece)")
            }
            vocab[piece] = id
        }
        self.vocab = vocab

        var ranks = [Pair: Int](minimumCapacity: rawMerges.count)
        for (rank, merge) in rawMerges.enumerated() {
            guard merge.count == 2 else {
                throw BentiTokenizerError.malformedTokenizerFile("merge entry with \(merge.count) parts")
            }
            ranks[Pair(left: merge[0], right: merge[1])] = rank
        }
        self.mergeRanks = ranks

        var added = [String: Int64](minimumCapacity: rawAdded.count)
        for entry in rawAdded {
            guard
                let content = entry["content"] as? String,
                let id = (entry["id"] as? NSNumber)?.int64Value
            else {
                throw BentiTokenizerError.malformedTokenizerFile("bad added_tokens entry")
            }
            added[content] = id
        }
        self.addedTokens = added

        // Standalone-word matcher for added tokens (`single_word: true`),
        // with `rstrip: true` consuming trailing whitespace. The 4 specials
        // (<s> etc.) are folded into the same matcher — they never occur in
        // benti input, and matching them as words is harmless here.
        let alternation = added.keys
            .sorted { $0.count > $1.count }
            .map(NSRegularExpression.escapedPattern(for:))
            .joined(separator: "|")
        self.addedTokenMatcher = try NSRegularExpression(
            pattern: "(?:^|(?<=\\s))(\(alternation))(?=\\s|$)\\s*"
        )
    }

    /// Encode a tagged, preprocessed string (`"eng_Latn pan_Guru <tokens>"`)
    /// into encoder-ready ids: `[4, 46, <pieces...>, 2]`.
    func encode(tagged text: String) -> [Int64] {
        var ids: [Int64] = []

        // 1. Split out added tokens.
        var cursor = text.startIndex
        let range = NSRange(text.startIndex..., in: text)
        addedTokenMatcher.enumerateMatches(in: text, range: range) { match, _, _ in
            guard
                let match,
                let full = Range(match.range, in: text),
                let tokenRange = Range(match.range(at: 1), in: text)
            else { return }
            if full.lowerBound > cursor {
                ids.append(contentsOf: encodeSegment(String(text[cursor..<full.lowerBound])))
            }
            // Membership is guaranteed — the matcher was built from the keys.
            ids.append(addedTokens[String(text[tokenRange])] ?? Self.unkId)
            cursor = full.upperBound
        }
        if cursor < text.endIndex {
            ids.append(contentsOf: encodeSegment(String(text[cursor...])))
        }

        // 5. Post-processor appends </s>.
        ids.append(Self.eosId)

        // 6. Extended-vocab remap.
        return ids.map { $0 >= Self.srcDictSize ? Self.unkId : $0 }
    }

    // MARK: - Plain-text segments

    private func encodeSegment(_ segment: String) -> [Int64] {
        // 2. NFKC, then collapse runs of 2+ spaces (Replace normalizer).
        var text = segment.precomposedStringWithCompatibilityMapping
        while text.contains("  ") {
            text = text.replacingOccurrences(of: "  ", with: " ")
        }

        // 3. Metaspace: split on U+0020, prepend U+2581 to every word.
        var ids: [Int64] = []
        for word in text.split(separator: " ") {
            ids.append(contentsOf: bpe(word: "\u{2581}" + word))
        }
        return ids
    }

    private func bpe(word: String) -> [Int64] {
        var symbols = word.unicodeScalars.map { String($0) }
        guard !symbols.isEmpty else { return [] }

        while symbols.count >= 2 {
            var bestRank = Int.max
            var bestIndex = -1
            for i in 0..<(symbols.count - 1) {
                if let rank = mergeRanks[Pair(left: symbols[i], right: symbols[i + 1])],
                   rank < bestRank {
                    bestRank = rank
                    bestIndex = i
                }
            }
            guard bestIndex >= 0 else { break }
            symbols.replaceSubrange(
                bestIndex...(bestIndex + 1),
                with: [symbols[bestIndex] + symbols[bestIndex + 1]]
            )
        }

        // fuse_unk: false — every out-of-vocab symbol is its own <unk>.
        return symbols.map { vocab[$0] ?? Self.unkId }
    }
}
