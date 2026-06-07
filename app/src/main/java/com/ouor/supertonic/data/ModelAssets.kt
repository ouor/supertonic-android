package com.ouor.supertonic.data

import android.content.Context
import java.io.File

/**
 * Declares the Supertonic 3 model assets hosted on Hugging Face and resolves their
 * locations in app-private internal storage. Layout mirrors the upstream repo:
 *
 *   <filesDir>/supertonic/onnx/{*.onnx, tts.json, unicode_indexer.json}
 *   <filesDir>/supertonic/voice_styles/{M1..M5,F1..F5}.json
 */
object ModelAssets {

    /** Hugging Face resolve base for Supertone/supertonic-3 @ main. */
    const val HF_BASE = "https://huggingface.co/Supertone/supertonic-3/resolve/main/"

    /** A remote file. [size] is the expected byte length, or -1 when unknown (small JSON). */
    data class Asset(val path: String, val size: Long)

    val ONNX: List<Asset> = listOf(
        Asset("onnx/duration_predictor.onnx", 3_700_147),
        Asset("onnx/text_encoder.onnx", 36_416_150),
        Asset("onnx/vector_estimator.onnx", 256_534_781),
        Asset("onnx/vocoder.onnx", 101_424_195),
        Asset("onnx/tts.json", 8_253),
        Asset("onnx/unicode_indexer.json", 277_676),
    )

    val VOICE_NAMES: List<String> = listOf("M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5")

    val VOICE_STYLES: List<Asset> = VOICE_NAMES.map { Asset("voice_styles/$it.json", -1) }

    val ALL: List<Asset> = ONNX + VOICE_STYLES

    /** Sum of known asset sizes (unknown sizes contribute 0); used for the progress total. */
    val KNOWN_TOTAL_BYTES: Long = ALL.sumOf { if (it.size > 0) it.size else 0L }

    fun baseDir(context: Context): File = File(context.filesDir, "supertonic")
    fun onnxDir(context: Context): File = File(baseDir(context), "onnx")
    fun voiceStylesDir(context: Context): File = File(baseDir(context), "voice_styles")

    fun localFile(context: Context, asset: Asset): File = File(baseDir(context), asset.path)
    fun voiceStyleFile(context: Context, name: String): File = File(voiceStylesDir(context), "$name.json")

    /** True only when every asset is present (and size-matches when the size is known). */
    fun isReady(context: Context): Boolean = ALL.all { asset ->
        val f = localFile(context, asset)
        f.exists() && (asset.size < 0 || f.length() == asset.size)
    }
}
