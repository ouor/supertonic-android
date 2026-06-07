package com.ouor.supertonic.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads the Supertonic model assets from Hugging Face into internal storage on
 * first run. Supports resume (HTTP Range), per-file retry, size validation, and
 * atomic publication (download to `*.part`, then rename).
 */
class ModelDownloader(private val context: Context) {

    sealed interface Progress {
        /** Emitted as bytes arrive. [downloadedBytes]/[totalBytes] are cumulative across all files. */
        data class Running(
            val fileIndex: Int,
            val fileCount: Int,
            val currentPath: String,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : Progress

        object Done : Progress
        data class Failed(val message: String) : Progress
    }

    /** Cold flow that performs the download when collected. Runs on Dispatchers.IO. */
    fun download(): Flow<Progress> = flow {
        val assets = ModelAssets.ALL
        ModelAssets.onnxDir(context).mkdirs()
        ModelAssets.voiceStylesDir(context).mkdirs()

        val total = ModelAssets.KNOWN_TOTAL_BYTES
        var cumulativeBefore = 0L

        try {
            assets.forEachIndexed { index, asset ->
                val finalFile = ModelAssets.localFile(context, asset)

                // Skip assets already complete.
                if (finalFile.exists() && (asset.size < 0 || finalFile.length() == asset.size)) {
                    cumulativeBefore += knownSize(asset)
                    emit(running(index, assets.size, asset, cumulativeBefore, total))
                    return@forEachIndexed
                }

                val base = cumulativeBefore
                downloadOne(asset, finalFile) { fileBytes ->
                    emit(running(index, assets.size, asset, base + fileBytes, total))
                }
                cumulativeBefore += knownSize(asset)
            }
            emit(Progress.Done)
        } catch (e: Exception) {
            emit(Progress.Failed(e.message ?: e.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    private fun running(i: Int, n: Int, a: ModelAssets.Asset, done: Long, total: Long) =
        Progress.Running(i, n, a.path, done, total)

    private fun knownSize(a: ModelAssets.Asset): Long = if (a.size > 0) a.size else 0L

    private suspend inline fun downloadOne(
        asset: ModelAssets.Asset,
        finalFile: File,
        crossinline onBytes: suspend (fileBytes: Long) -> Unit,
    ) {
        val part = File(finalFile.parentFile, finalFile.name + ".part")
        var lastError: Exception? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                transfer(asset, part) { onBytes(it) }
                // Validate size when known.
                if (asset.size >= 0 && part.length() != asset.size) {
                    throw IllegalStateException(
                        "Size mismatch for ${asset.path}: got ${part.length()}, expected ${asset.size}"
                    )
                }
                if (finalFile.exists()) finalFile.delete()
                if (!part.renameTo(finalFile)) {
                    throw IllegalStateException("Failed to publish ${finalFile.name}")
                }
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt == MAX_ATTEMPTS - 1) {
                    part.delete() // give up cleanly on the last failure
                }
            }
        }
        throw lastError ?: IllegalStateException("Download failed: ${asset.path}")
    }

    private suspend inline fun transfer(
        asset: ModelAssets.Asset,
        part: File,
        crossinline onBytes: suspend (fileBytes: Long) -> Unit,
    ) {
        val existing = if (part.exists()) part.length() else 0L
        val conn = (URL(ModelAssets.HF_BASE + asset.path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "supertonic-android")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            conn.connect()
            val code = conn.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !resuming) {
                throw IllegalStateException("HTTP $code for ${asset.path}")
            }
            // If the server ignored our Range, restart from scratch.
            val startFrom = if (resuming) existing else 0L

            FileOutputStream(part, resuming).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var fileBytes = startFrom
                    var sinceEmit = 0L
                    while (true) {
                        coroutineContext.ensureActive() // cooperative cancellation
                        val read = input.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        fileBytes += read
                        sinceEmit += read
                        if (sinceEmit >= EMIT_EVERY_BYTES) {
                            onBytes(fileBytes)
                            sinceEmit = 0L
                        }
                    }
                    onBytes(fileBytes)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val BUFFER_SIZE = 64 * 1024
        const val EMIT_EVERY_BYTES = 512L * 1024
    }
}
