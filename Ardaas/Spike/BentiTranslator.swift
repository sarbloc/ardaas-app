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
    /// Time inside `ORTSession` creation for this translation. Zero when
    /// sessions are held across translations.
    let sessionLoadSeconds: Double
    /// Highest `phys_footprint` sampled while this translation ran. Sampled off
    /// thread because the dominant allocation lives and dies inside one ORT run.
    let peakFootprintBytes: Int64
    /// Footprint at each pipeline boundary, for the lab screen's breakdown.
    let stages: [MemoryStage]
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

    /// Memory levers, so the lab screen can measure the pre-optimization
    /// baseline and the optimized pipeline in a single cable run.
    struct Options: Equatable, Sendable {
        /// Load ORT-optimized graphs whose initializers are memory-mapped from
        /// a side-car file instead of read onto the heap.
        var useOptimizedGraphs: Bool
        /// Create each graph's session immediately before it is needed and
        /// release it immediately after. The encoder is only needed for step 1
        /// and `decoder_model` only for the first decode step, so holding all
        /// three costs ~350 MB of weights (and their arenas) for nothing.
        ///
        /// Implies copying every tensor that outlives its producing session out
        /// of ORT-owned memory — which is also a large win in its own right,
        /// because an ORT-owned output tensor pins the whole arena region it
        /// was allocated from.
        var releaseSessionsBetweenStages: Bool

        /// Reproduces the pre-optimization pipeline (measured 1374 MB peak on
        /// an iPhone 15 Pro).
        static let baseline = Options(
            useOptimizedGraphs: false, releaseSessionsBetweenStages: false)
        static let optimized = Options(
            useOptimizedGraphs: true, releaseSessionsBetweenStages: true)
    }

    /// Time spent in `init`: optimized-graph preparation (first run only) plus
    /// tokenizer/dictionary load plus, in baseline mode, session creation.
    let modelLoadSeconds: Double
    let options: Options
    /// Directory the ONNX graphs are actually loaded from — the optimized cache
    /// when it was built successfully, otherwise the raw download.
    let graphDirectory: URL

    private let env: ORTEnv
    private let tokenizer: BentiTokenizer
    private let idToPiece: [Int64: String]
    /// Populated only when `releaseSessionsBetweenStages` is false.
    private var heldSessions: [String: ORTSession] = [:]

    init(
        modelDirectory: URL,
        options: Options = .optimized,
        onProgress: (String) -> Void = { _ in }
    ) throws {
        let start = Date()
        self.options = options

        func file(_ name: String) throws -> URL {
            let url = modelDirectory.appendingPathComponent(name)
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw BentiTranslatorError.missingModelFile(name)
            }
            return url
        }

        let env = try ORTEnv(loggingLevel: .warning)
        self.env = env

        for name in ModelOptimizer.graphNames { _ = try file(name) }
        let graphDirectory: URL
        if options.useOptimizedGraphs {
            onProgress("Optimizing graphs for memory-mapped weights…")
            graphDirectory = try ModelOptimizer.prepare(
                modelDirectory: modelDirectory, env: env, onProgress: onProgress)
        } else {
            graphDirectory = modelDirectory
        }
        self.graphDirectory = graphDirectory

        onProgress("Loading tokenizer…")
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

        if !options.releaseSessionsBetweenStages {
            // Baseline behaviour: every graph resident for the translator's
            // whole lifetime, so `modelLoadSeconds` stays comparable.
            onProgress("Creating ONNX sessions…")
            var held: [String: ORTSession] = [:]
            for name in ModelOptimizer.graphNames {
                held[name] = try Self.makeSession(
                    name, env: env, graphDirectory: graphDirectory)
            }
            self.heldSessions = held
        }

        self.modelLoadSeconds = Date().timeIntervalSince(start)
    }

    private static func makeSession(
        _ name: String, env: ORTEnv, graphDirectory: URL
    ) throws -> ORTSession {
        try ORTSession(
            env: env,
            modelPath: graphDirectory.appendingPathComponent(name).path,
            sessionOptions: nil
        )
    }

    /// Accumulates session-creation time across the stages of one translation.
    /// A reference box rather than an `inout` parameter so it can be used from
    /// inside the per-stage `autoreleasepool` closures.
    private final class LoadClock {
        var seconds: Double = 0
    }

    /// Returns the held session when sessions are pinned, otherwise a fresh one
    /// the caller is expected to drop as soon as it is done with it.
    private func session(_ name: String, clock: LoadClock) throws -> ORTSession {
        if let held = heldSessions[name] { return held }
        let start = Date()
        let session = try Self.makeSession(name, env: env, graphDirectory: graphDirectory)
        clock.seconds += Date().timeIntervalSince(start)
        return session
    }

    // MARK: - Translate

    func translate(_ english: String) throws -> BentiTranslation {
        let start = Date()
        let sampler = MemoryProbe.Sampler()
        var stages: [MemoryStage] = [
            MemoryStage(label: "Before translate", footprintBytes: MemoryProbe.footprintBytes())
        ]
        let clock = LoadClock()
        defer { sampler.stop() }

        let pre = BentiPreprocessor.preprocess(english)
        let inputIds = tokenizer.encode(tagged: pre.tagged)
        let generated = try greedyDecode(inputIds: inputIds, stages: &stages, clock: clock)

        var text = Self.decodeToText(ids: generated, idToPiece: idToPiece)
        text = Self.restorePlaceholders(text, placeholders: pre.placeholders)
        let devanagari = text
        text = Self.transliterateDevanagariToGurmukhi(text)
        text = Self.indicDetokenize(text)

        let latency = Date().timeIntervalSince(start)
        stages.append(
            MemoryStage(label: "After release", footprintBytes: MemoryProbe.footprintBytes()))

        return BentiTranslation(
            gurmukhi: text,
            rawDevanagari: devanagari,
            generatedTokenCount: generated.count,
            latencySeconds: latency,
            sessionLoadSeconds: clock.seconds,
            peakFootprintBytes: sampler.stop(),
            stages: stages,
            isGurmukhiPure: Self.isGurmukhiPure(text)
        )
    }

    // MARK: - Greedy decode loop (spec section 4)

    private func greedyDecode(
        inputIds: [Int64], stages: inout [MemoryStage], clock: LoadClock
    ) throws -> [Int64] {
        let sourceLength = inputIds.count
        let mask = [Int64](repeating: 1, count: sourceLength)
        let maskValue = try Self.int64Tensor(mask, shape: [1, sourceLength])

        // Stage 1: encoder. Its output is the only thing that outlives it.
        let encoderHidden = try runEncoder(
            inputIds: inputIds, maskValue: maskValue, clock: clock)
        stages.append(
            MemoryStage(label: "After encoder", footprintBytes: MemoryProbe.footprintBytes()))

        // Stage 2: decoder without past, one step. decoder_start_token_id == </s>.
        let (firstId, past) = try runFirstDecodeStep(
            encoderHidden: encoderHidden, maskValue: maskValue, clock: clock)
        stages.append(
            MemoryStage(label: "After decode step 1", footprintBytes: MemoryProbe.footprintBytes()))

        // Stage 3: decoder with past for the remaining steps.
        let generated = try runDecodeLoop(
            firstId: firstId, past: past, maskValue: maskValue, clock: clock)
        stages.append(
            MemoryStage(label: "After decode loop", footprintBytes: MemoryProbe.footprintBytes()))

        return generated
    }

    private func runEncoder(
        inputIds: [Int64], maskValue: ORTValue, clock: LoadClock
    ) throws -> ORTValue {
        try autoreleasepool {
            let encoder = try session("encoder_model.onnx", clock: clock)
            let inputIdsValue = try Self.int64Tensor(inputIds, shape: [1, inputIds.count])
            let outputs = try encoder.run(
                withInputs: ["input_ids": inputIdsValue, "attention_mask": maskValue],
                outputNames: ["last_hidden_state"],
                runOptions: nil
            )
            guard let hidden = outputs["last_hidden_state"] else {
                throw BentiTranslatorError.sessionFailure("encoder returned no last_hidden_state")
            }
            return try detachIfNeeded(hidden)
        }
    }

    private func runFirstDecodeStep(
        encoderHidden: ORTValue, maskValue: ORTValue, clock: LoadClock
    ) throws -> (nextId: Int64, past: [String: ORTValue]) {
        try autoreleasepool {
            let decoder = try session("decoder_model.onnx", clock: clock)
            let startValue = try Self.int64Tensor([Self.eos], shape: [1, 1])
            // All `present.*` outputs are needed to seed the KV cache.
            let step1 = try decoder.run(
                withInputs: [
                    "input_ids": startValue,
                    "encoder_attention_mask": maskValue,
                    "encoder_hidden_states": encoderHidden,
                ],
                outputNames: Set(try decoder.outputNames()),
                runOptions: nil
            )
            guard let logits = step1["logits"] else {
                throw BentiTranslatorError.sessionFailure("decoder returned no logits")
            }
            let nextId = try Self.argmaxLastPosition(logits: logits)

            // KV cache: all 18 layers x {decoder, encoder} x {key, value}.
            var past: [String: ORTValue] = [:]
            for layer in 0..<Self.layerCount {
                for kind in ["decoder", "encoder"] {
                    for kv in ["key", "value"] {
                        let name = "\(layer).\(kind).\(kv)"
                        guard let value = step1["present.\(name)"] else {
                            throw BentiTranslatorError.sessionFailure("missing present.\(name)")
                        }
                        past["past_key_values.\(name)"] = try detachIfNeeded(value)
                    }
                }
            }
            return (nextId, past)
        }
    }

    private func runDecodeLoop(
        firstId: Int64, past initialPast: [String: ORTValue], maskValue: ORTValue,
        clock: LoadClock
    ) throws -> [Int64] {
        var past = initialPast
        var nextId = firstId
        var generated: [Int64] = [nextId]

        let decoderWithPast = try session("decoder_with_past_model.onnx", clock: clock)
        // The `present.*.encoder.*` outputs are Identity pass-throughs of the
        // corresponding inputs, and the loop feeds the step-1 tensors back
        // unchanged, so requesting them only buys 36 needless copies per step.
        var wanted: Set<String> = ["logits"]
        for layer in 0..<Self.layerCount {
            for kv in ["key", "value"] { wanted.insert("present.\(layer).decoder.\(kv)") }
        }

        while nextId != Self.eos && generated.count < Self.maxNewTokens {
            try autoreleasepool {
                var feed = past
                feed["input_ids"] = try Self.int64Tensor([nextId], shape: [1, 1])
                feed["encoder_attention_mask"] = maskValue

                let step = try decoderWithPast.run(
                    withInputs: feed, outputNames: wanted, runOptions: nil)
                guard let stepLogits = step["logits"] else {
                    throw BentiTranslatorError.sessionFailure(
                        "decoder_with_past returned no logits")
                }
                nextId = try Self.argmaxLastPosition(logits: stepLogits)
                generated.append(nextId)

                for layer in 0..<Self.layerCount {
                    for kv in ["key", "value"] {
                        let name = "\(layer).decoder.\(kv)"
                        guard let value = step["present.\(name)"] else {
                            throw BentiTranslatorError.sessionFailure("missing present.\(name)")
                        }
                        past["past_key_values.\(name)"] = try detachIfNeeded(value)
                    }
                }
            }
        }

        // Drop the trailing EOS (and any stray specials are dropped in decode).
        if generated.last == Self.eos { generated.removeLast() }
        return generated
    }

    /// Copies an ORT-owned output tensor into caller-owned memory.
    ///
    /// Two reasons, both load-bearing:
    /// 1. Correctness — `tensorData` hands back the session's own buffer, so a
    ///    value produced by a session that is about to be released must be
    ///    copied before that happens.
    /// 2. Memory — an ORT-owned tensor pins the arena region it came from. On
    ///    the reference host, keeping the 72 step-1 KV tensors ORT-owned held a
    ///    whole 251 MB arena region alive for the entire decode loop; copying
    ///    them out cost no measurable time and freed all of it.
    ///
    /// No-op when sessions are pinned for the translator's lifetime, so the
    /// baseline preset measures the original behaviour.
    private func detachIfNeeded(_ value: ORTValue) throws -> ORTValue {
        guard options.releaseSessionsBetweenStages else { return value }
        let info = try value.tensorTypeAndShapeInfo()
        let source = try value.tensorData()
        let copy = NSMutableData(bytes: source.bytes, length: source.length)
        return try ORTValue(
            tensorData: copy, elementType: info.elementType, shape: info.shape)
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
