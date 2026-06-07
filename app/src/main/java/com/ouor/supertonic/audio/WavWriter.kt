package com.ouor.supertonic.audio

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes mono float32 PCM as a 16-bit little-endian WAV stream. Mirrors the reference
 * `writeWavFile` (java/Helper.java) — no external dependency needed for WAV output.
 */
object WavWriter {

    fun writeTo(out: OutputStream, pcm: FloatArray, sampleRate: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size * 2
        val riffSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
        header.putInt(riffSize)
        header.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())
        header.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
        header.putInt(16)                       // PCM fmt chunk size
        header.putShort(1)                      // audio format = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
        header.putInt(dataSize)
        out.write(header.array())

        // Stream PCM samples in chunks to avoid allocating the whole buffer twice.
        val chunkSamples = 8192
        val buf = ByteBuffer.allocate(chunkSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < pcm.size) {
            buf.clear()
            val end = minOf(i + chunkSamples, pcm.size)
            for (j in i until end) {
                val v = (pcm[j] * 32767f).coerceIn(-32768f, 32767f).toInt()
                buf.putShort(v.toShort())
            }
            out.write(buf.array(), 0, (end - i) * 2)
            i = end
        }
    }

    fun writeFile(file: File, pcm: FloatArray, sampleRate: Int) {
        file.outputStream().buffered().use { writeTo(it, pcm, sampleRate) }
    }
}
