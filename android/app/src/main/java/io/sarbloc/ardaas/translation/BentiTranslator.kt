package io.sarbloc.ardaas.translation

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/**
 * The ONNX half of the en → pan_Guru pipeline: a 3-session greedy decode
 * (encoder / decoder / decoder_with_past with KV-cache feedback) over the
 * ORT-optimized graphs, wrapped in the pure text stages — port of
 * `Ardaas/Translation/BentiTranslator.swift`.
 *
 * Memory shape, all of it load-bearing (docs/translation/MEMORY.md):
 *
 * - **Sequential session lifecycle.** Each session is created immediately
 *   before its stage and closed at the end of it: encoder → close →
 *   `decoder_model` → close → `decoder_with_past` → close. No two sessions are
 *   ever open at once and nothing is held between translations. Worth 294 MB.
 * - **Detached tensors.** Every output that outlives its `Result` is copied
 *   into caller-owned memory first: a correctness requirement once the
 *   producing session closes, and separately worth 285 MB because an
 *   ORT-owned tensor pins the arena region it came from.
 * - **Memory-mapped weights.** [graphDirectory] is the optimized cache, whose
 *   initializers (prepacked ones included) live in side-cars that ORT maps
 *   rather than reads onto the heap.
 *
 * Every `OrtSession`, `OnnxTensor` and `Result` is closed on every path —
 * success, failure and cancellation. Short-lived ones by `use {}`; the ones
 * that cross a stage boundary live in a [DetachedTensors] opened *before* the
 * stage that fills it, so there is no window in which a copy exists that
 * nothing will close.
 *
 * Holds only the tokenizer and target dictionary (~7 MB) between calls.
 * [TranslationEngine] owns its lifetime and serialises calls.
 *
 * @param graphDirectory the optimized cache, not the raw download.
 * @param vocabularyDirectory where `tokenizer_src.json` and `dict.TGT.json`
 *   live — separate because the originals they were downloaded beside are
 *   deleted once the cache is built.
 * @throws TranslationError.MissingModelFile when a graph or vocabulary file
 *   is absent, [TranslationError.MalformedModelFile] when one is unreadable as
 *   what it claims to be, [TranslationError.SessionFailure] when ONNX Runtime
 *   cannot start.
 */
class BentiTranslator(
    catalog: ModelCatalog,
    private val graphDirectory: File,
    vocabularyDirectory: File,
) : BentiTranslating {
    private val tokenizer: BentiTokenizer
    private val idToPiece: Map<Long, String>
    private val env: OrtEnvironment

    init {
        for (name in catalog.graphNames) {
            if (!File(graphDirectory, name).isFile) throw TranslationError.MissingModelFile("Optimized/$name")
        }
        tokenizer = BentiTokenizer(vocabularyFile(vocabularyDirectory, TOKENIZER_FILE))
        idToPiece = loadTargetDictionary(vocabularyFile(vocabularyDirectory, DICTIONARY_FILE))
        env = ortEnvironment()
    }

    override suspend fun translate(english: String): BentiTranslation {
        val started = System.nanoTime()
        val pre = BentiPreprocessor.preprocess(english)
        val inputIds = tokenizer.encode(pre.tagged)
        BentiTokenizer.validateSourceLength(inputIds)

        val clock = LoadClock()
        val generated = greedyDecode(inputIds, clock)

        val devanagari = BentiPostprocessor.restorePlaceholders(
            BentiPostprocessor.decodeToText(generated, idToPiece),
            pre.placeholders,
        )
        val gurmukhi = BentiPostprocessor.indicDetokenize(
            BentiPostprocessor.transliterateDevanagariToGurmukhi(devanagari),
        )
        return BentiTranslation(
            gurmukhi = gurmukhi,
            rawDevanagari = devanagari,
            generatedIds = generated,
            latencyMillis = (System.nanoTime() - started) / NANOS_PER_MILLI,
            sessionLoadMillis = clock.nanos / NANOS_PER_MILLI,
            isGurmukhiPure = BentiPostprocessor.isGurmukhiPure(gurmukhi),
        )
    }

    // Greedy decode (spec section 4)

    private suspend fun greedyDecode(inputIds: LongArray, clock: LoadClock): List<Long> =
        int64Tensor(LongArray(inputIds.size) { 1L }).use { mask ->
            DetachedTensors().use { encoderState ->
                DetachedTensors().use { cache ->
                    // Stage 1: encoder. Its hidden state is all that outlives it.
                    runEncoder(inputIds, mask, clock, into = encoderState)
                    // Stage 2: decoder without past, one step from </s>.
                    val firstId = runFirstDecodeStep(encoderState, mask, clock, into = cache)
                    // The hidden state is consumed: the encoder-side KV entries
                    // now carry it. Drop it before the long stage, not after.
                    encoderState.close()
                    // Stage 3: decoder with past for every remaining step.
                    runDecodeLoop(firstId, cache, mask, clock)
                }
            }
        }

    private suspend fun runEncoder(inputIds: LongArray, mask: OnnxTensor, clock: LoadClock, into: DetachedTensors) {
        currentCoroutineContext().ensureActive()
        inStage(ENCODER) {
            openSession(ENCODER, clock).use { encoder ->
                int64Tensor(inputIds).use { ids ->
                    encoder.run(mapOf("input_ids" to ids, "attention_mask" to mask), setOf(ENCODER_OUTPUT))
                        .use { outputs -> into.copyFrom(outputs, ENCODER_STATE) }
                }
            }
        }
    }

    /** Returns the first generated id and seeds [into] with all 72 KV entries. */
    private suspend fun runFirstDecodeStep(
        encoderState: DetachedTensors,
        mask: OnnxTensor,
        clock: LoadClock,
        into: DetachedTensors,
    ): Long {
        currentCoroutineContext().ensureActive()
        return inStage(DECODER) {
            openSession(DECODER, clock).use { decoder ->
                int64Tensor(longArrayOf(BentiTokenizer.EOS_ID)).use { start ->
                    val inputs = HashMap<String, OnnxTensor>(encoderState.tensors)
                    inputs["input_ids"] = start
                    inputs["encoder_attention_mask"] = mask
                    // Every present.* output is needed to seed the KV cache.
                    decoder.run(inputs, decoder.outputNames).use { outputs ->
                        into.copyFrom(outputs, ALL_KV)
                        argmaxLastPosition(output(outputs, LOGITS))
                    }
                }
            }
        }
    }

    private suspend fun runDecodeLoop(firstId: Long, cache: DetachedTensors, mask: OnnxTensor, clock: LoadClock): List<Long> {
        currentCoroutineContext().ensureActive()
        val generated = mutableListOf(firstId)
        var nextId = firstId
        inStage(DECODER_WITH_PAST) {
            openSession(DECODER_WITH_PAST, clock).use { session ->
                while (nextId != BentiTokenizer.EOS_ID && generated.size < MAX_NEW_TOKENS) {
                    // The Android side of #52: a cancelled caller stops here,
                    // between steps, and gets the cancellation — never a
                    // partial translation. An ORT run in flight finishes first.
                    currentCoroutineContext().ensureActive()
                    nextId = decodeStep(session, nextId, cache, mask)
                    generated += nextId
                }
            }
        }
        // Drop the trailing </s> (stray specials are dropped in decode).
        if (generated.last() == BentiTokenizer.EOS_ID) generated.removeAt(generated.lastIndex)
        return generated
    }

    /** One decoder_with_past step: returns the next id and moves [cache] forward. */
    private fun decodeStep(session: OrtSession, previousId: Long, cache: DetachedTensors, mask: OnnxTensor): Long =
        int64Tensor(longArrayOf(previousId)).use { ids ->
            val inputs = HashMap<String, OnnxTensor>(cache.tensors)
            inputs["input_ids"] = ids
            inputs["encoder_attention_mask"] = mask
            session.run(inputs, LOOP_OUTPUTS).use { outputs ->
                // Only the self-attention entries change. The encoder-side
                // present.* outputs are Identity pass-throughs of their inputs,
                // so the step-1 copies are fed back unchanged and never requested.
                cache.copyFrom(outputs, DECODER_KV)
                argmaxLastPosition(output(outputs, LOGITS))
            }
        }

    // Sessions

    /** Accumulates session-creation time across the stages of one translation. */
    private class LoadClock {
        var nanos: Long = 0
    }

    /** Always a fresh session, which the caller closes at the end of its stage. */
    private fun openSession(name: String, clock: LoadClock): OrtSession {
        val started = System.nanoTime()
        try {
            return env.openSession(File(graphDirectory, name))
        } finally {
            clock.nanos += System.nanoTime() - started
        }
    }

    /** Names the stage in any runtime failure, so a SessionFailure says which graph broke. */
    private inline fun <T> inStage(graph: String, block: () -> T): T = try {
        block()
    } catch (e: OrtException) {
        throw TranslationError.SessionFailure("$graph: ${e.message ?: e}")
    }

    // Tensors

    /**
     * Tensors that outlive the `Result` they came from, copied into
     * caller-owned memory and keyed by the input name they feed next. Owns
     * every tensor in it: [copyFrom] closes what it replaces, [close] the rest.
     * Idempotent to close, so a stage can release it early inside its `use`.
     *
     * Each slot is double-buffered with direct buffers it reuses across steps.
     * A JVM direct buffer is only freed when the GC finds it, so a fresh one
     * per KV entry per step piled up ~250 MB of dead copies over a 124-token
     * decode on the host. With reuse a slot allocates only when its entry
     * outgrows both buffers, which happens a handful of times per translation.
     */
    private inner class DetachedTensors : AutoCloseable {
        private val owned = HashMap<String, OnnxTensor>()

        /** The direct buffer behind each tensor in [owned]. */
        private val backing = HashMap<String, ByteBuffer>()

        /** Per slot, the buffer not behind a live tensor: the next copy's destination. */
        private val spare = HashMap<String, ByteBuffer>()

        val tensors: Map<String, OnnxTensor> get() = owned

        /**
         * Copies each output named by a key of [routes] out of [outputs] — which
         * must still be open — and stores it under the matching value. Copies
         * land in spare buffers only and all are made before anything is
         * replaced, so a failure part-way leaves the live tensors untouched
         * and leaks none of the new ones.
         */
        fun copyFrom(outputs: OrtSession.Result, routes: Map<String, String>) {
            val fresh = HashMap<String, Pair<OnnxTensor, ByteBuffer>>(routes.size)
            try {
                for ((outputName, inputName) in routes) {
                    val source = floatTensor(output(outputs, outputName), outputName)
                    val destination = spareBuffer(inputName, source.info.numElements * Float.SIZE_BYTES)
                    fresh[inputName] = detach(source, into = destination) to destination
                }
            } catch (e: Throwable) {
                fresh.values.forEach { (tensor, _) -> tensor.close() }
                throw e
            }
            for ((name, copy) in fresh) {
                val (tensor, buffer) = copy
                owned.put(name, tensor)?.close()
                // The buffer behind the tensor just closed is free for the next copy.
                val retired = backing.put(name, buffer)
                if (retired != null) spare[name] = retired else spare.remove(name)
            }
        }

        private fun spareBuffer(name: String, bytes: Long): ByteBuffer {
            if (bytes !in 0..Int.MAX_VALUE) throw TranslationError.SessionFailure("$name is $bytes bytes, cannot copy")
            spare[name]?.takeIf { it.capacity() >= bytes }?.let { return it }
            // Headroom so a KV entry growing one position per step does not
            // reallocate every step.
            val capacity = minOf(Int.MAX_VALUE.toLong(), bytes + bytes / 2).toInt()
            return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder()).also { spare[name] = it }
        }

        override fun close() {
            owned.values.forEach(OnnxTensor::close)
            owned.clear()
            backing.clear()
            spare.clear()
        }
    }

    /**
     * Copies an ORT-owned float32 output into [into] and wraps it as a tensor
     * — the direct buffer is used in place, not copied again. Must run before
     * the producing `Result` is closed.
     *
     * Two reasons, both load-bearing: the source buffer belongs to the Result
     * and dies with it, and an ORT-owned tensor pins the arena region it was
     * allocated from — on the reference host the 72 step-1 KV tensors held a
     * whole 251 MB region alive for the entire decode loop.
     */
    private fun detach(source: OnnxTensor, into: ByteBuffer): OnnxTensor {
        into.clear()
        // getFloatBuffer is the public API's only read of native memory, and
        // it returns a heap copy; that one is short-lived garbage.
        val view = into.asFloatBuffer().put(source.floatBuffer)
        view.flip()
        return OnnxTensor.createTensor(env, view, source.info.shape)
    }

    private fun floatTensor(value: OnnxValue, name: String): OnnxTensor {
        val tensor = value as? OnnxTensor ?: throw TranslationError.SessionFailure("$name is not a tensor")
        val type = tensor.info.type
        if (type != OnnxJavaType.FLOAT) throw TranslationError.SessionFailure("$name is $type, expected FLOAT")
        return tensor
    }

    /** A `[1, n]` int64 tensor. */
    private fun int64Tensor(values: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(values), longArrayOf(1, values.size.toLong()))

    companion object {
        const val LAYER_COUNT = 18

        /** Generated-token cap, trailing `</s>` included; the decoder's positional limit. */
        const val MAX_NEW_TOKENS = 256

        const val ENCODER = "encoder_model.onnx"
        const val DECODER = "decoder_model.onnx"
        const val DECODER_WITH_PAST = "decoder_with_past_model.onnx"
        const val TOKENIZER_FILE = "tokenizer_src.json"
        const val DICTIONARY_FILE = "dict.TGT.json"

        private const val ENCODER_OUTPUT = "last_hidden_state"
        private const val LOGITS = "logits"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Encoder output → the decoder input it feeds. */
        private val ENCODER_STATE = mapOf(ENCODER_OUTPUT to "encoder_hidden_states")

        /** `present.<layer>.<decoder|encoder>.<key|value>` → `past_key_values.…`, all 18 layers. */
        private val ALL_KV: Map<String, String> = (0 until LAYER_COUNT)
            .flatMap { layer ->
                listOf("decoder", "encoder").flatMap { kind -> listOf("key", "value").map { "$layer.$kind.$it" } }
            }
            .associate { "present.$it" to "past_key_values.$it" }

        /** The self-attention half of [ALL_KV] — the only entries that change after step 1. */
        private val DECODER_KV: Map<String, String> = ALL_KV.filterKeys { ".decoder." in it }

        private val LOOP_OUTPUTS: Set<String> = setOf(LOGITS) + DECODER_KV.keys

        private fun vocabularyFile(directory: File, name: String): File =
            File(directory, name).takeIf { it.isFile } ?: throw TranslationError.MissingModelFile(name)

        /** `dict.TGT.json` maps piece → id; decoding needs the reverse. */
        private fun loadTargetDictionary(file: File): Map<Long, String> {
            val pieceToId = try {
                Json.decodeFromString<Map<String, Long>>(file.readText(Charsets.UTF_8))
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException.
                throw TranslationError.MalformedModelFile(file.name, "not a piece -> id object: ${e.message}")
            }
            return pieceToId.entries.associate { (piece, id) -> id to piece }
        }

        private fun output(outputs: OrtSession.Result, name: String): OnnxValue =
            outputs.get(name).orElse(null) ?: throw TranslationError.SessionFailure("missing output $name")

        /**
         * Argmax over the vocab dimension at the last position of a
         * `[1, seq, vocab]` float32 logits tensor. Ties go to the lowest id,
         * as numpy's argmax does in the reference pipeline.
         */
        private fun argmaxLastPosition(logits: OnnxValue): Long {
            val tensor = logits as? OnnxTensor ?: throw TranslationError.DecodeFailure("logits is not a tensor")
            val info = tensor.info
            val vocabSize = info.shape.lastOrNull() ?: 0L
            if (vocabSize <= 0) throw TranslationError.DecodeFailure("logits tensor has no vocab dimension")
            if (info.type != OnnxJavaType.FLOAT) {
                throw TranslationError.DecodeFailure("logits are ${info.type}, expected FLOAT")
            }
            val floats = tensor.floatBuffer
            val total = floats.remaining()
            if (total < vocabSize) {
                throw TranslationError.DecodeFailure("logits tensor smaller than vocab ($total < $vocabSize)")
            }
            val start = total - vocabSize.toInt()
            var best = start
            var bestValue = Float.NEGATIVE_INFINITY
            for (i in start until total) {
                val value = floats[i]
                if (value > bestValue) {
                    bestValue = value
                    best = i
                }
            }
            return (best - start).toLong()
        }
    }
}
