import Foundation
import OnnxRuntimeBindings

// Spike (#35): the full en -> pan_Guru pipeline per the pipeline spec:
// preprocess -> tokenize -> 3-session ONNX greedy decode (encoder / decoder /
// decoder_with_past with KV-cache feedback) -> id-drop + piece-join
// detokenization from dict.TGT.json -> Devanagari -> Gurmukhi transliteration
// -> indic detokenize.
//
// The ONNX parts require the downloaded model files (see ModelDownloader);
// the pure text stages are static and unit-tested without the model.

enum BentiTranslatorError: Error, CustomStringConvertible {
    case missingModelFile(String)
    case malformedTargetDictionary(String)
    case sessionFailure(String)
    case decodeFailure(String)

    var description: String {
        switch self {
        case .missingModelFile(let name): return "Missing model file: \(name)"
        case .malformedTargetDictionary(let detail): return "Malformed dict.TGT.json: \(detail)"
        case .sessionFailure(let detail): return "ONNX session failure: \(detail)"
        case .decodeFailure(let detail): return "Decode failure: \(detail)"
        }
    }
}

struct BentiTranslation {
    let gurmukhi: String
    /// Model-raw output after decode + cleanup, before transliteration.
    let rawDevanagari: String
    let generatedTokenCount: Int
    let latencySeconds: Double
    /// Script purity gate (spec section 5.5): no letters/marks/numbers
    /// outside U+0A00–U+0A7F.
    let isGurmukhiPure: Bool
}

/// Not thread-safe; call from one task at a time. Marked @unchecked Sendable
/// so the lab view can hop it across task boundaries (spike-grade).
final class BentiTranslator: @unchecked Sendable {
    static let layerCount = 18
    static let eos: Int64 = 2
    static let maxNewTokens = 256

    let modelLoadSeconds: Double

    private let env: ORTEnv
    private let encoder: ORTSession
    private let decoder: ORTSession
    private let decoderWithPast: ORTSession
    private let decoderOutputNames: [String]
    private let decoderWithPastOutputNames: [String]
    private let tokenizer: BentiTokenizer
    private let idToPiece: [Int64: String]

    init(modelDirectory: URL) throws {
        let start = Date()

        func file(_ name: String) throws -> URL {
            let url = modelDirectory.appendingPathComponent(name)
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw BentiTranslatorError.missingModelFile(name)
            }
            return url
        }

        self.env = try ORTEnv(loggingLevel: .warning)
        self.encoder = try ORTSession(
            env: env, modelPath: try file("encoder_model.onnx").path, sessionOptions: nil)
        self.decoder = try ORTSession(
            env: env, modelPath: try file("decoder_model.onnx").path, sessionOptions: nil)
        self.decoderWithPast = try ORTSession(
            env: env, modelPath: try file("decoder_with_past_model.onnx").path, sessionOptions: nil)
        self.decoderOutputNames = try decoder.outputNames()
        self.decoderWithPastOutputNames = try decoderWithPast.outputNames()

        self.tokenizer = try BentiTokenizer(tokenizerFile: try file("tokenizer_src.json"))

        let dictData = try Data(contentsOf: try file("dict.TGT.json"))
        guard let pieceToId = try JSONSerialization.jsonObject(with: dictData) as? [String: Any] else {
            throw BentiTranslatorError.malformedTargetDictionary("not a piece -> id object")
        }
        var idToPiece = [Int64: String](minimumCapacity: pieceToId.count)
        for (piece, id) in pieceToId {
            guard let id = (id as? NSNumber)?.int64Value else {
                throw BentiTranslatorError.malformedTargetDictionary("non-numeric id for \(piece)")
            }
            idToPiece[id] = piece
        }
        self.idToPiece = idToPiece

        self.modelLoadSeconds = Date().timeIntervalSince(start)
    }

    // MARK: - Translate

    func translate(_ english: String) throws -> BentiTranslation {
        let start = Date()

        let pre = BentiPreprocessor.preprocess(english)
        let inputIds = tokenizer.encode(tagged: pre.tagged)
        let generated = try greedyDecode(inputIds: inputIds)

        var text = Self.decodeToText(ids: generated, idToPiece: idToPiece)
        text = Self.restorePlaceholders(text, placeholders: pre.placeholders)
        let devanagari = text
        text = Self.transliterateDevanagariToGurmukhi(text)
        text = Self.indicDetokenize(text)

        return BentiTranslation(
            gurmukhi: text,
            rawDevanagari: devanagari,
            generatedTokenCount: generated.count,
            latencySeconds: Date().timeIntervalSince(start),
            isGurmukhiPure: Self.isGurmukhiPure(text)
        )
    }

    // MARK: - Greedy decode loop (spec section 4)

    private func greedyDecode(inputIds: [Int64]) throws -> [Int64] {
        let sourceLength = inputIds.count
        let mask = [Int64](repeating: 1, count: sourceLength)

        let inputIdsValue = try Self.int64Tensor(inputIds, shape: [1, sourceLength])
        let maskValue = try Self.int64Tensor(mask, shape: [1, sourceLength])

        let encoderOutputs = try encoder.run(
            withInputs: ["input_ids": inputIdsValue, "attention_mask": maskValue],
            outputNames: ["last_hidden_state"],
            runOptions: nil
        )
        guard let encoderHidden = encoderOutputs["last_hidden_state"] else {
            throw BentiTranslatorError.sessionFailure("encoder returned no last_hidden_state")
        }

        // Step 1: decoder without past; decoder_start_token_id == </s> == 2.
        let startValue = try Self.int64Tensor([Self.eos], shape: [1, 1])
        let step1 = try decoder.run(
            withInputs: [
                "input_ids": startValue,
                "encoder_attention_mask": maskValue,
                "encoder_hidden_states": encoderHidden,
            ],
            outputNames: Set(decoderOutputNames),
            runOptions: nil
        )
        guard let logits = step1["logits"] else {
            throw BentiTranslatorError.sessionFailure("decoder returned no logits")
        }
        var nextId = try Self.argmaxLastPosition(logits: logits)
        var generated: [Int64] = [nextId]

        // KV cache: all 18 layers x {decoder, encoder} x {key, value}.
        var past: [String: ORTValue] = [:]
        for layer in 0..<Self.layerCount {
            for kind in ["decoder", "encoder"] {
                for kv in ["key", "value"] {
                    let name = "\(layer).\(kind).\(kv)"
                    guard let value = step1["present.\(name)"] else {
                        throw BentiTranslatorError.sessionFailure("missing present.\(name)")
                    }
                    past["past_key_values.\(name)"] = value
                }
            }
        }

        // Steps 2..N: decoder with past; only the decoder KV entries advance.
        while nextId != Self.eos && generated.count < Self.maxNewTokens {
            var feed = past
            feed["input_ids"] = try Self.int64Tensor([nextId], shape: [1, 1])
            feed["encoder_attention_mask"] = maskValue

            let step = try decoderWithPast.run(
                withInputs: feed,
                outputNames: Set(decoderWithPastOutputNames),
                runOptions: nil
            )
            guard let stepLogits = step["logits"] else {
                throw BentiTranslatorError.sessionFailure("decoder_with_past returned no logits")
            }
            nextId = try Self.argmaxLastPosition(logits: stepLogits)
            generated.append(nextId)

            for layer in 0..<Self.layerCount {
                for kv in ["key", "value"] {
                    let name = "\(layer).decoder.\(kv)"
                    guard let value = step["present.\(name)"] else {
                        throw BentiTranslatorError.sessionFailure("missing present.\(name)")
                    }
                    past["past_key_values.\(name)"] = value
                }
            }
        }

        // Drop the trailing EOS (and any stray specials are dropped in decode).
        if generated.last == Self.eos { generated.removeLast() }
        return generated
    }

    private static func int64Tensor(_ values: [Int64], shape: [Int]) throws -> ORTValue {
        let data = values.withUnsafeBufferPointer { buffer in
            NSMutableData(bytes: buffer.baseAddress, length: buffer.count * MemoryLayout<Int64>.stride)
        }
        return try ORTValue(
            tensorData: data,
            elementType: .int64,
            shape: shape.map { NSNumber(value: $0) }
        )
    }

    /// Argmax over the vocab dimension at the last position of a
    /// `[1, seq, vocab]` float32 logits tensor.
    private static func argmaxLastPosition(logits: ORTValue) throws -> Int64 {
        let info = try logits.tensorTypeAndShapeInfo()
        guard let vocabSize = info.shape.last?.intValue, vocabSize > 0 else {
            throw BentiTranslatorError.decodeFailure("logits tensor has no vocab dimension")
        }
        let data = try logits.tensorData() as Data
        let totalFloats = data.count / MemoryLayout<Float32>.stride
        guard totalFloats >= vocabSize else {
            throw BentiTranslatorError.decodeFailure(
                "logits tensor smaller than vocab (\(totalFloats) < \(vocabSize))")
        }
        let start = totalFloats - vocabSize
        return data.withUnsafeBytes { raw -> Int64 in
            let floats = raw.bindMemory(to: Float32.self)
            var bestIndex = start
            var bestValue = -Float.infinity
            for i in start..<totalFloats where floats[i] > bestValue {
                bestValue = floats[i]
                bestIndex = i
            }
            return Int64(bestIndex - start)
        }
    }

    // MARK: - Ids -> raw Devanagari string (spec section 5.1)

    static func decodeToText(ids: [Int64], idToPiece: [Int64: String]) -> String {
        // Drop specials (< 4), join pieces, U+2581 -> space, trim.
        let joined = ids
            .filter { $0 >= 4 }
            .compactMap { idToPiece[$0] }
            .joined()
            .replacingOccurrences(of: "\u{2581}", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleanUpTokenizationSpaces(joined)
    }

    /// HF `clean_up_tokenization_spaces` replacements, in reference order.
    static func cleanUpTokenizationSpaces(_ text: String) -> String {
        [
            (" .", "."), (" ?", "?"), (" !", "!"), (" ,", ","),
            (" ' ", "'"), (" n't", "n't"), (" 'm", "'m"), (" 's", "'s"),
            (" 've", "'ve"), (" 're", "'re"),
        ].reduce(text) { $0.replacingOccurrences(of: $1.0, with: $1.1) }
    }

    // MARK: - Placeholder restore (spec section 5.2)

    static func restorePlaceholders(
        _ text: String,
        placeholders: [(alias: String, original: String)]
    ) -> String {
        placeholders.reduce(text) { $0.replacingOccurrences(of: $1.alias, with: $1.original) }
    }

    // MARK: - Devanagari -> Gurmukhi (spec section 5.3)

    static func transliterateDevanagariToGurmukhi(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            let offset = Int(scalar.value) - 0x0900
            if (0x00...0x6F).contains(offset),
               scalar.value != 0x0964, scalar.value != 0x0965,  // danda, double danda
               let mapped = Unicode.Scalar(0x0A00 + offset) {
                out.append(mapped)
            } else {
                out.append(scalar)
            }
        }
        return String(out)
    }

    // MARK: - Indic detokenize (spec section 5.4; indic_nlp_library
    // trivial_detokenize_indic, ported bug-for-bug)

    private static let numberSequence = try! NSRegularExpression(
        pattern: "([0-9]+ [,.:/] )+[0-9]+")
    private static let bothAttach = try! NSRegularExpression(pattern: "[ ]([-/\\\\])[ ]")
    private static let leftAttach = try! NSRegularExpression(
        pattern: "[ ]([!%)\\]},.:;>?\u{0964}\u{0965}])")
    private static let rightAttach = try! NSRegularExpression(pattern: "([#$(\\[{<@])[ ]")

    static func indicDetokenize(_ text: String) -> String {
        var s = text

        // Number sequences: collapse inner spaces. The reference skips a
        // match that starts exactly at the previous boundary (`start > prev`),
        // which leaves a leading match untouched — kept for parity.
        var parts: [String] = []
        var prev = s.startIndex
        numberSequence.enumerateMatches(in: s, range: NSRange(s.startIndex..., in: s)) { match, _, _ in
            guard let match, let r = Range(match.range, in: s) else { return }
            if r.lowerBound > prev {
                parts.append(String(s[prev..<r.lowerBound]))
                parts.append(String(s[r]).replacingOccurrences(of: " ", with: ""))
                prev = r.upperBound
            }
        }
        parts.append(String(s[prev...]))
        s = parts.joined()

        let full = { NSRange(s.startIndex..., in: s) }
        s = bothAttach.stringByReplacingMatches(in: s, range: full(), withTemplate: "$1")
        s = leftAttach.stringByReplacingMatches(in: s, range: full(), withTemplate: "$1")
        s = rightAttach.stringByReplacingMatches(in: s, range: full(), withTemplate: "$1")

        // Quotes alternate: odd occurrences attach right, even attach left.
        for quote in ["'", "\"", "`"] {
            var count = 0
            var marked = ""
            for char in s {
                if String(char) == quote {
                    marked += count % 2 == 0 ? "@RA" : "@LA"
                    count += 1
                } else {
                    marked.append(char)
                }
            }
            s = marked
                .replacingOccurrences(of: "@RA ", with: quote)
                .replacingOccurrences(of: " @LA", with: quote)
                .replacingOccurrences(of: "@RA", with: quote)
                .replacingOccurrences(of: "@LA", with: quote)
        }
        return s
    }

    // MARK: - Script purity gate (spec section 5.5)

    static func isGurmukhiPure(_ text: String) -> Bool {
        for scalar in text.unicodeScalars {
            if (0x0A00...0x0A7F).contains(scalar.value) { continue }
            switch scalar.properties.generalCategory {
            case .lowercaseLetter, .uppercaseLetter, .titlecaseLetter,
                 .modifierLetter, .otherLetter,
                 .nonspacingMark, .spacingMark, .enclosingMark,
                 .decimalNumber, .letterNumber, .otherNumber:
                return false
            default:
                continue
            }
        }
        return true
    }
}
