package com.ouor.supertonic.tts

import org.json.JSONObject
import java.io.File

/**
 * Model configuration loaded from `tts.json`.
 * Mirrors the `ae` / `ttl` fields read in the reference implementations.
 */
data class TtsConfig(
    val sampleRate: Int,
    val baseChunkSize: Int,
    val chunkCompressFactor: Int,
    val latentDim: Int,
) {
    companion object {
        fun load(file: File): TtsConfig {
            val root = JSONObject(file.readText())
            val ae = root.getJSONObject("ae")
            val ttl = root.getJSONObject("ttl")
            return TtsConfig(
                sampleRate = ae.getInt("sample_rate"),
                baseChunkSize = ae.getInt("base_chunk_size"),
                chunkCompressFactor = ttl.getInt("chunk_compress_factor"),
                latentDim = ttl.getInt("latent_dim"),
            )
        }
    }
}
