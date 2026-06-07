package com.ouor.supertonic.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Plays mono float32 PCM through an [AudioTrack], routed to a user-selected output
 * device via [AudioTrack.setPreferredDevice] (API 28+). One playback at a time.
 */
class PcmPlayer(private val sampleRate: Int = 44_100) {

    @Volatile
    private var track: AudioTrack? = null

    /**
     * Play [pcm] to [preferredDevice] (null = system default routing). Suspends until
     * playback finishes; cancellation stops playback immediately.
     */
    suspend fun play(pcm: FloatArray, preferredDevice: AudioDeviceInfo?) = withContext(Dispatchers.IO) {
        stop()
        if (pcm.isEmpty()) return@withContext

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).let { if (it > 0) it else DEFAULT_MIN_BUF } * 2

        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        preferredDevice?.let { at.preferredDevice = it }
        track = at

        try {
            at.play()
            var offset = 0
            while (offset < pcm.size) {
                coroutineContext.ensureActive()
                val written = at.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written < 0) error("AudioTrack.write failed: $written")
                offset += written
            }
            // write() only copies into the track buffer; wait for the buffered tail to
            // actually render before stop()/release(), otherwise the end gets cut off.
            val totalFrames = pcm.size
            var lastPos = -1
            var stalls = 0
            while (coroutineContext.isActive) {
                if (at.playState != AudioTrack.PLAYSTATE_PLAYING) break
                val pos = at.playbackHeadPosition
                if (pos >= totalFrames) break
                if (pos == lastPos) {
                    if (++stalls >= MAX_STALLS) break // underrun / silent failure safeguard
                } else {
                    stalls = 0
                    lastPos = pos
                }
                delay(POLL_MS)
            }
            at.stop()
        } finally {
            releaseTrack(at)
            if (track === at) track = null
        }
    }

    /** Stop and release any in-flight playback. */
    fun stop() {
        track?.let { releaseTrack(it) }
        track = null
    }

    private fun releaseTrack(at: AudioTrack) {
        runCatching {
            if (at.playState != AudioTrack.PLAYSTATE_STOPPED) {
                at.pause()
                at.flush()
            }
        }
        runCatching { at.release() }
    }

    private companion object {
        const val DEFAULT_MIN_BUF = 16 * 1024
        const val POLL_MS = 20L
        const val MAX_STALLS = 50 // ~1s of no progress -> give up waiting
    }
}
