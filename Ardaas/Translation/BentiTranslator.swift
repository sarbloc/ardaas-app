#if BENTI_ONNX
import Foundation
import OnnxRuntimeBindings

/// The ONNX half of the en → pan_Guru pipeline: a 3-session greedy decode
/// (encoder / decoder / decoder_with_past with KV-cache feedback) over the
/// ORT-optimized graphs, wrapped in the pure text stages.
///
/// Memory shape, all of it load-bearing (see docs/translation/MEMORY.md):
///
/// * **Sequential session lifecycle.** Each session is created immediately
///   before it is needed and dropped immediately after: encoder → release →
///   `decoder_model` → release → `decoder_with_past` → release. Nothing is held
///   between translations, so peak does not accumulate across sentences and
///   idle footprint returns to baseline. Worth 294 MB.
/// * **Detached tensors.** `ORTValue.tensorData()` hands back the session's own
///   buffer, so anything outliving its producing session must be copied — a
///   correctness requirement of the above, and separately worth 285 MB because
///   an ORT-owned tensor pins the entire arena region it came from.
/// * **Memory-mapped weights.** The graphs loaded here are the optimized ones,
///   whose initializers live in a side-car and are mapped rather than read onto
///   the heap. Worth 215 MB, and makes session creation ~4.8x faster.
///
/// The spike also carried a "baseline" mode that reproduced the original
/// 1374 MB configuration for A/B measurement. It is deliberately not ported:
/// keeping a known-fatal memory path alive in shipping code to make a graph
/// look good is not a trade worth making. The comparison lives in the docs.
///
/// Not thread-safe by design; `TranslationEngine` owns and serialises it.
final class BentiTranslator: BentiTranslating {
    static let layerCount = 18
    static let eos: Int64 = 2
    static let maxNewTokens = 256

    private let env: ORTEnv
    private let graphDirectory: URL
    private let tokenizer: BentiTokenizer
    private let idToPiece: [Int64: String]

    /// - Parameters:
    ///   - graphDirectory: the optimized cache, not the raw download.
    ///   - vocabularyDirectory: where `tokenizer_src.json` and `dict.TGT.json`
    ///     live. Separate because the originals they were downloaded beside are
    ///     deleted once the cache is built.
    init(catalog: ModelCatalog, graphDirectory: URL, vocabularyDirectory: URL) throws {
        func vocabularyFile(_ name: String) throws -> URL {
            let url = vocabularyDirectory.appendingPathComponent(name)
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw TranslationError.missingModelFile(name)
            }
            return url
        }

        for name in catalog.graphNames {
            let url = graphDirectory.appendingPathComponent(name)
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw TranslationError.missingModelFile("Optimized/" + name)
            }
        }

        self.env = try ORTEnv(loggingLevel: .warning)
        self.graphDirectory = graphDirectory
        self.tokenizer = try BentiTokenizer(tokenizerFile: try vocabularyFile("tokenizer_src.json"))

        let dictURL = try vocabularyFile("dict.TGT.json")
        let dictData = try Data(contentsOf: dictURL)
        guard let pieceToId = try JSONSerialization.jsonObject(with: dictData) as? [String: Any]
        else {
            throw TranslationError.malformedModelFile(
                name: "dict.TGT.json", detail: "not a piece -> id object")
        }
        var idToPiece = [Int64: String](minimumCapacity: pieceToId.count)
        for (piece, id) in pieceToId {
            guard let id = (id as? NSNumber)?.int64Value else {
                throw TranslationError.malformedModelFile(
                    name: "dict.TGT.json", detail: "non-numeric id for \(piece)")
            }
            idToPiece[id] = piece
        }
        self.idToPiece = idToPiece
    }

    // MARK: - Translate

    func translate(_ english: String, collectMetrics: Bool) throws -> BentiTranslation {
        let start = Date()
        let sampler = collectMetrics ? MemoryProbe.Sampler() : nil
        defer { sampler?.stop() }

        var stages: [MemoryStage] = []
        if collectMetrics {
            let entry = MemoryProbe.footprintBytes()
            stages.append(
                MemoryStage(label: "Before translate", peakBytes: entry, footprintBytes: entry))
        }
        let clock = LoadClock()

        let pre = BentiPreprocessor.preprocess(english)
        let inputIds = tokenizer.encode(tagged: pre.tagged)
        try BentiTokenizer.validateSourceLength(inputIds)

        let generated = try greedyDecode(
            inputIds: inputIds, stages: &stages, clock: clock, sampler: sampler)

        var text = BentiPostprocessor.decodeToText(ids: generated, idToPiece: idToPiece)
        text = BentiPostprocessor.restorePlaceholders(text, placeholders: pre.placeholders)
        let devanagari = text
        text = BentiPostprocessor.transliterateDevanagariToGurmukhi(text)
        text = BentiPostprocessor.indicDetokenize(text)

        let latency = Date().timeIntervalSince(start)
        record("Postprocess", into: &stages, sampler: sampler)

        return BentiTranslation(
            gurmukhi: text,
            rawDevanagari: devanagari,
            generatedTokenCount: generated.count,
            latencySeconds: latency,
            sessionLoadSeconds: clock.seconds,
            peakFootprintBytes: sampler?.stop() ?? 0,
            stages: stages,
            isGurmukhiPure: BentiPostprocessor.isGurmukhiPure(text)
        )
    }

    // MARK: - Sessions

    /// Accumulates session-creation time across the stages of one translation.
    /// A reference box rather than an `inout` parameter so it can be used from
    /// inside the per-stage `autoreleasepool` closures.
    private final class LoadClock {
        var seconds: Double = 0
    }

    /// Always a fresh session, and always dropped by the caller as soon as it
    /// is done with it.
    private func makeSession(_ name: String, clock: LoadClock) throws -> ORTSession {
        let start = Date()
        do {
            let session = try ORTSession(
                env: env,
                modelPath: graphDirectory.appendingPathComponent(name).path,
                sessionOptions: nil
            )
            clock.seconds += Date().timeIntervalSince(start)
            return session
        } catch {
            throw TranslationError.sessionFailure("\(name): \(error)")
        }
    }

    private func record(
        _ label: String, into stages: inout [MemoryStage], sampler: MemoryProbe.Sampler?
    ) {
        guard let sampler else { return }
        stages.append(
            MemoryStage(
                label: label,
                peakBytes: sampler.takeWindowPeak(),
                footprintBytes: MemoryProbe.footprintBytes()))
    }

    // MARK: - Greedy decode loop (spec section 4)

    private func greedyDecode(
        inputIds: [Int64], stages: inout [MemoryStage], clock: LoadClock,
        sampler: MemoryProbe.Sampler?
    ) throws -> [Int64] {
        let sourceLength = inputIds.count
        let mask = [Int64](repeating: 1, count: sourceLength)
        let maskValue = try Self.int64Tensor(mask, shape: [1, sourceLength])

        // Stage 1: encoder. Its output is the only thing that outlives it.
        let encoderHidden = try runEncoder(
            inputIds: inputIds, maskValue: maskValue, clock: clock)
        record("Encoder", into: &stages, sampler: sampler)

        // Stage 2: decoder without past, one step. decoder_start_token_id == </s>.
        let (firstId, past) = try runFirstDecodeStep(
            encoderHidden: encoderHidden, maskValue: maskValue, clock: clock)
        record("Decode step 1", into: &stages, sampler: sampler)

        // Stage 3: decoder with past for the remaining steps.
        let generated = try runDecodeLoop(
            firstId: firstId, past: past, maskValue: maskValue, clock: clock)
        record("Decode loop", into: &stages, sampler: sampler)

        return generated
    }

    private func runEncoder(
        inputIds: [Int64], maskValue: ORTValue, clock: LoadClock
    ) throws -> ORTValue {
        try autoreleasepool {
            let encoder = try makeSession("encoder_model.onnx", clock: clock)
            let inputIdsValue = try Self.int64Tensor(inputIds, shape: [1, inputIds.count])
            let outputs = try encoder.run(
                withInputs: ["input_ids": inputIdsValue, "attention_mask": maskValue],
                outputNames: ["last_hidden_state"],
                runOptions: nil
            )
            guard let hidden = outputs["last_hidden_state"] else {
                throw TranslationError.sessionFailure("encoder returned no last_hidden_state")
            }
            return try Self.detach(hidden)
        }
    }

    private func runFirstDecodeStep(
        encoderHidden: ORTValue, maskValue: ORTValue, clock: LoadClock
    ) throws -> (nextId: Int64, past: [String: ORTValue]) {
        try autoreleasepool {
            let decoder = try makeSession("decoder_model.onnx", clock: clock)
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
                throw TranslationError.sessionFailure("decoder returned no logits")
            }
            let nextId = try Self.argmaxLastPosition(logits: logits)

            // KV cache: all 18 layers x {decoder, encoder} x {key, value}.
            var past: [String: ORTValue] = [:]
            for layer in 0..<Self.layerCount {
                for kind in ["decoder", "encoder"] {
                    for kv in ["key", "value"] {
                        let name = "\(layer).\(kind).\(kv)"
                        guard let value = step1["present.\(name)"] else {
                            throw TranslationError.sessionFailure("missing present.\(name)")
                        }
                        past["past_key_values.\(name)"] = try Self.detach(value)
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

        let decoderWithPast = try makeSession("decoder_with_past_model.onnx", clock: clock)
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
                    throw TranslationError.sessionFailure(
                        "decoder_with_past returned no logits")
                }
                nextId = try Self.argmaxLastPosition(logits: stepLogits)
                generated.append(nextId)

                for layer in 0..<Self.layerCount {
                    for kv in ["key", "value"] {
                        let name = "\(layer).decoder.\(kv)"
                        guard let value = step["present.\(name)"] else {
                            throw TranslationError.sessionFailure("missing present.\(name)")
                        }
                        past["past_key_values.\(name)"] = try Self.detach(value)
                    }
                }
            }
        }

        // Drop the trailing EOS (stray specials are dropped in decode).
        if generated.last == Self.eos { generated.removeLast() }
        return generated
    }

    // MARK: - Tensors

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
    private static func detach(_ value: ORTValue) throws -> ORTValue {
        let info = try value.tensorTypeAndShapeInfo()
        let source = try value.tensorData()
        let copy = NSMutableData(bytes: source.bytes, length: source.length)
        return try ORTValue(tensorData: copy, elementType: info.elementType, shape: info.shape)
    }

    private static func int64Tensor(_ values: [Int64], shape: [Int]) throws -> ORTValue {
        let data = values.withUnsafeBufferPointer { buffer in
            NSMutableData(
                bytes: buffer.baseAddress, length: buffer.count * MemoryLayout<Int64>.stride)
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
            throw TranslationError.decodeFailure("logits tensor has no vocab dimension")
        }
        let data = try logits.tensorData() as Data
        let totalFloats = data.count / MemoryLayout<Float32>.stride
        guard totalFloats >= vocabSize else {
            throw TranslationError.decodeFailure(
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
}
#endif
