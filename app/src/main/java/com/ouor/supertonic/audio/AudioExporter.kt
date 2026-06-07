package com.ouor.supertonic.audio

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Encodes mono float32 PCM to a user-chosen file (SAF [Uri]) in the selected
 * [OutputFormat]. WAV is written directly; AAC/Opus/MP3 are encoded with FFmpegKit.
 *
 * Codec availability in the bundled FFmpeg build is probed at runtime so we can pick
 * the right encoder (and fail clearly if, e.g., libmp3lame is absent in the fork build).
 */
class AudioExporter(private val context: Context) {

    sealed interface Result {
        object Success : Result
        data class Failure(val message: String) : Result
    }

    suspend fun export(
        pcm: FloatArray,
        sampleRate: Int,
        format: OutputFormat,
        destination: Uri,
    ): Result = withContext(Dispatchers.IO) {
        try {
            when (format) {
                OutputFormat.WAV -> writeWav(pcm, sampleRate, destination)
                else -> encodeWithFfmpeg(pcm, sampleRate, format, destination)
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun writeWav(pcm: FloatArray, sampleRate: Int, destination: Uri): Result {
        val out = context.contentResolver.openOutputStream(destination)
            ?: return Result.Failure("Cannot open destination for writing")
        out.buffered().use { WavWriter.writeTo(it, pcm, sampleRate) }
        return Result.Success
    }

    private fun encodeWithFfmpeg(
        pcm: FloatArray,
        sampleRate: Int,
        format: OutputFormat,
        destination: Uri,
    ): Result {
        val codecArgs = codecArgsFor(format)
            ?: return Result.Failure(
                "The bundled FFmpeg build has no encoder for ${format.label}. " +
                    "Try a different format or a FFmpeg build that includes it."
            )

        val tmp = File.createTempFile("tts_pcm_", ".wav", context.cacheDir)
        try {
            WavWriter.writeFile(tmp, pcm, sampleRate)
            val safOut = FFmpegKitConfig.getSafParameterForWrite(context, destination)
                ?: return Result.Failure("Cannot resolve destination for writing")

            val command = "-y -i ${tmp.absolutePath} $codecArgs $safOut"
            val session = FFmpegKit.execute(command)
            return if (ReturnCode.isSuccess(session.returnCode)) {
                Result.Success
            } else {
                Result.Failure("FFmpeg failed (rc=${session.returnCode}): ${session.failStackTrace ?: ""}")
            }
        } finally {
            tmp.delete()
        }
    }

    /** Build encoder + container args for [format], or null if no supported encoder exists. */
    private fun codecArgsFor(format: OutputFormat): String? = when (format) {
        OutputFormat.AAC ->
            if (hasEncoder("aac")) "-c:a aac -b:a 192k -f mp4" else null
        OutputFormat.MP3 ->
            when {
                hasEncoder("libmp3lame") -> "-c:a libmp3lame -q:a 2 -f mp3"
                hasEncoder("libshine") -> "-c:a libshine -b:a 192k -f mp3"
                else -> null
            }
        OutputFormat.OPUS ->
            when {
                hasEncoder("libopus") -> "-c:a libopus -b:a 96k -f ogg"
                hasEncoder("opus") -> "-c:a opus -b:a 96k -strict -2 -f ogg"
                else -> null
            }
        OutputFormat.WAV -> "-c:a pcm_s16le -f wav"
    }

    fun hasEncoder(name: String): Boolean = encodersOutput().contains(" $name ")

    @Volatile
    private var encodersCache: String? = null

    private fun encodersOutput(): String {
        encodersCache?.let { return it }
        val session = FFmpegKit.execute("-hide_banner -encoders")
        // Pad lines so simple " name " containment matches whole tokens.
        val out = (session.allLogsAsString ?: "").lineSequence()
            .joinToString("\n") { " ${it.trim()} " }
        encodersCache = out
        return out
    }
}
