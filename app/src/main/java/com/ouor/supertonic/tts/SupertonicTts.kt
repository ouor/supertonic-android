package com.ouor.supertonic.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random

/**
 * On-device Supertonic 3 TTS engine. Runs the four ONNX models
 * (duration_predictor → text_encoder → vector_estimator ×N → vocoder),
 * faithfully porting the reference `TextToSpeech` (java/Helper.java, py/helper.py).
 *
 * Outputs mono float32 PCM at [config.sampleRate] (44.1 kHz).
 */
class SupertonicTts private constructor(
    private val env: OrtEnvironment,
    private val config: TtsConfig,
    private val processor: UnicodeProcessor,
    private val dp: OrtSession,
    private val textEnc: OrtSession,
    private val vectorEst: OrtSession,
    private val vocoder: OrtSession,
) : Closeable {

    val sampleRate: Int get() = config.sampleRate

    /** Synthesized mono float32 PCM plus per-item duration (seconds). */
    class Result(val wav: FloatArray, val duration: FloatArray)

    /**
     * Synthesize a single text with automatic long-form chunking and inter-chunk silence.
     */
    fun synthesize(
        text: String,
        lang: String,
        style: VoiceStyle,
        totalStep: Int = 8,
        speed: Float = 1.05f,
        silenceDuration: Float = 0.3f,
    ): Result {
        require(style.batchSize == 1) { "Single-text synthesis requires a single-speaker style" }

        val chunks = TextChunker.chunk(text, TextChunker.maxLenForLang(lang))
        val out = ArrayList<Float>()
        var durCat = 0f

        chunks.forEachIndexed { i, chunk ->
            val r = infer(listOf(chunk), listOf(lang), style, totalStep, speed)
            val dur = r.duration[0]
            val wavLen = (sampleRate * dur).toInt().coerceAtMost(r.wav.size)

            if (i > 0) {
                repeat((silenceDuration * sampleRate).toInt()) { out.add(0f) }
                durCat += silenceDuration
            }
            for (s in 0 until wavLen) out.add(r.wav[s])
            durCat += dur
        }

        return Result(out.toFloatArray(), floatArrayOf(durCat))
    }

    /** Batch synthesize multiple texts in one inference pass (no chunking/silence). */
    fun batch(
        textList: List<String>,
        langList: List<String>,
        style: VoiceStyle,
        totalStep: Int = 8,
        speed: Float = 1.05f,
    ): Result = infer(textList, langList, style, totalStep, speed)

    // --- core inference --------------------------------------------------- //

    private fun infer(
        textList: List<String>,
        langList: List<String>,
        style: VoiceStyle,
        totalStep: Int,
        speed: Float,
    ): Result {
        val bsz = textList.size
        val tok = processor.process(textList, langList)
        val maxLen = if (tok.textIds.isEmpty()) 0 else tok.textIds[0].size

        val textIdsTensor = longTensor(flatten(tok.textIds), longArrayOf(bsz.toLong(), maxLen.toLong()))
        val textMaskTensor = floatTensor(flattenMask(tok.textMask), longArrayOf(bsz.toLong(), 1, maxLen.toLong()))
        val styleTtl = floatTensor(style.ttlData, style.ttlShape)
        val styleDp = floatTensor(style.dpData, style.dpShape)

        val owned = mutableListOf(textIdsTensor, textMaskTensor, styleTtl, styleDp)
        try {
            // 1) Duration predictor
            val duration: FloatArray
            dp.run(mapOf("text_ids" to textIdsTensor, "style_dp" to styleDp, "text_mask" to textMaskTensor)).use { r ->
                duration = readFloats(r[0] as OnnxTensor)
            }
            for (i in duration.indices) duration[i] /= speed

            // 2) Text encoder (text_emb reused across the denoising loop)
            val textEncResult = textEnc.run(
                mapOf("text_ids" to textIdsTensor, "style_ttl" to styleTtl, "text_mask" to textMaskTensor)
            )
            try {
                val textEmb = textEncResult[0] as OnnxTensor

                // 3) Sample noisy latent + reusable masks
                val latent = sampleNoisyLatent(duration)
                val latentShape = longArrayOf(bsz.toLong(), latent.latentDim.toLong(), latent.latentLen.toLong())
                val latentMaskTensor = floatTensor(latent.maskFlat, longArrayOf(bsz.toLong(), 1, latent.latentLen.toLong()))
                val totalStepTensor = floatTensor(FloatArray(bsz) { totalStep.toFloat() }, longArrayOf(bsz.toLong()))
                owned += latentMaskTensor
                owned += totalStepTensor

                // Vector estimator denoising loop
                var xt = latent.noisyFlat
                for (step in 0 until totalStep) {
                    val noisyTensor = floatTensor(xt, latentShape)
                    val stepTensor = floatTensor(FloatArray(bsz) { step.toFloat() }, longArrayOf(bsz.toLong()))
                    vectorEst.run(
                        mapOf(
                            "noisy_latent" to noisyTensor,
                            "text_emb" to textEmb,
                            "style_ttl" to styleTtl,
                            "latent_mask" to latentMaskTensor,
                            "text_mask" to textMaskTensor,
                            "current_step" to stepTensor,
                            "total_step" to totalStepTensor,
                        )
                    ).use { r -> xt = readFloats(r[0] as OnnxTensor) }
                    noisyTensor.close()
                    stepTensor.close()
                }

                // 4) Vocoder
                val finalLatent = floatTensor(xt, latentShape)
                val wav: FloatArray
                try {
                    vocoder.run(mapOf("latent" to finalLatent)).use { r ->
                        wav = readFloats(r[0] as OnnxTensor)
                    }
                } finally {
                    finalLatent.close()
                }
                return Result(wav, duration)
            } finally {
                textEncResult.close()
            }
        } finally {
            owned.forEach { it.close() }
        }
    }

    private class Latent(
        val noisyFlat: FloatArray,
        val maskFlat: FloatArray,
        val latentDim: Int,
        val latentLen: Int,
    )

    private fun sampleNoisyLatent(duration: FloatArray): Latent {
        val bsz = duration.size
        val maxDur = duration.maxOrNull() ?: 0f
        val wavLenMax = (maxDur * sampleRate).toLong()

        val chunkSize = config.baseChunkSize * config.chunkCompressFactor
        val latentLen = ((wavLenMax + chunkSize - 1) / chunkSize).toInt()
        val latentDim = config.latentDim * config.chunkCompressFactor

        // Per-item latent lengths for masking.
        val latentLengths = IntArray(bsz) {
            val wavLen = (duration[it] * sampleRate).toLong()
            ((wavLen + chunkSize - 1) / chunkSize).toInt()
        }

        val maskFlat = FloatArray(bsz * latentLen)
        for (b in 0 until bsz) {
            for (t in 0 until latentLen) {
                maskFlat[b * latentLen + t] = if (t < latentLengths[b]) 1f else 0f
            }
        }

        val rng = Random()
        val noisyFlat = FloatArray(bsz * latentDim * latentLen)
        for (b in 0 until bsz) {
            for (d in 0 until latentDim) {
                val base = (b * latentDim + d) * latentLen
                for (t in 0 until latentLen) {
                    noisyFlat[base + t] = rng.nextGaussian().toFloat() * maskFlat[b * latentLen + t]
                }
            }
        }
        return Latent(noisyFlat, maskFlat, latentDim, latentLen)
    }

    override fun close() {
        dp.close(); textEnc.close(); vectorEst.close(); vocoder.close()
    }

    // --- tensor helpers --------------------------------------------------- //

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun readFloats(t: OnnxTensor): FloatArray {
        val fb = t.floatBuffer
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }

    private fun flatten(rows: Array<LongArray>): LongArray {
        if (rows.isEmpty()) return LongArray(0)
        val cols = rows[0].size
        val out = LongArray(rows.size * cols)
        for (i in rows.indices) System.arraycopy(rows[i], 0, out, i * cols, cols)
        return out
    }

    private fun flattenMask(rows: Array<FloatArray>): FloatArray {
        if (rows.isEmpty()) return FloatArray(0)
        val cols = rows[0].size
        val out = FloatArray(rows.size * cols)
        for (i in rows.indices) System.arraycopy(rows[i], 0, out, i * cols, cols)
        return out
    }

    companion object {
        /**
         * Load the engine from a directory containing the four `.onnx` models,
         * `tts.json`, and `unicode_indexer.json`.
         */
        fun load(modelDir: File, options: OrtSession.SessionOptions? = null): SupertonicTts {
            val env = OrtEnvironment.getEnvironment()
            val opts = options ?: OrtSession.SessionOptions()

            val config = TtsConfig.load(File(modelDir, "tts.json"))
            val indexer = loadIndexer(File(modelDir, "unicode_indexer.json"))
            val processor = UnicodeProcessor(indexer)

            fun session(name: String) = env.createSession(File(modelDir, name).absolutePath, opts)

            return SupertonicTts(
                env = env,
                config = config,
                processor = processor,
                dp = session("duration_predictor.onnx"),
                textEnc = session("text_encoder.onnx"),
                vectorEst = session("vector_estimator.onnx"),
                vocoder = session("vocoder.onnx"),
            )
        }

        /** unicode_indexer.json is a flat JSON array: unicode value (0..65535) -> token id. */
        private fun loadIndexer(file: File): IntArray {
            val arr = JSONArray(file.readText())
            return IntArray(arr.length()) { arr.getInt(it) }
        }
    }
}
