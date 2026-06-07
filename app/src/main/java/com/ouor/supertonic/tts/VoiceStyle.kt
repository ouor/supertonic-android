package com.ouor.supertonic.tts

import org.json.JSONObject
import java.io.File

/**
 * A preset/custom voice identity. Holds the two style tensors (`style_ttl`, `style_dp`)
 * as flat float data plus their shapes, decoupled from any ORT environment so a style
 * can be loaded and kept around independently of the engine.
 *
 * Shapes are `[1, d1, d2]` for a single voice (batch size 1).
 */
class VoiceStyle(
    val ttlData: FloatArray,
    val ttlShape: LongArray,
    val dpData: FloatArray,
    val dpShape: LongArray,
) {
    /** Number of speakers represented (first dim). */
    val batchSize: Int get() = ttlShape[0].toInt()

    companion object {
        fun load(file: File): VoiceStyle {
            val root = JSONObject(file.readText())
            val (ttlData, ttlShape) = parseTensor(root.getJSONObject("style_ttl"))
            val (dpData, dpShape) = parseTensor(root.getJSONObject("style_dp"))
            return VoiceStyle(ttlData, ttlShape, dpData, dpShape)
        }

        /** Parse a {dims:[...], data:[[[...]]]} tensor node into (flat data, shape). */
        private fun parseTensor(node: JSONObject): Pair<FloatArray, LongArray> {
            val dimsJson = node.getJSONArray("dims")
            val shape = LongArray(dimsJson.length()) { dimsJson.getLong(it) }
            val total = shape.fold(1L) { acc, d -> acc * d }.toInt()
            val flat = FloatArray(total)

            var idx = 0
            val d0 = node.getJSONArray("data")
            for (i in 0 until d0.length()) {
                val d1 = d0.getJSONArray(i)
                for (j in 0 until d1.length()) {
                    val d2 = d1.getJSONArray(j)
                    for (k in 0 until d2.length()) {
                        flat[idx++] = d2.getDouble(k).toFloat()
                    }
                }
            }
            return flat to shape
        }
    }
}
