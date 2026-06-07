package com.ouor.supertonic.audio

/** Audio file formats the app can export. */
enum class OutputFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    WAV("WAV", "wav", "audio/wav"),
    AAC("AAC (M4A)", "m4a", "audio/mp4"),
    OPUS("Opus (OGG)", "ogg", "audio/ogg"),
    MP3("MP3", "mp3", "audio/mpeg");

    /** Default filename (without directory) for a given base name. */
    fun fileName(base: String): String = "$base.$extension"
}
