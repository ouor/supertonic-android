package com.ouor.supertonic.tts

/** Supported language codes for Supertonic 3 multilingual TTS (31 languages + "na"). */
object Languages {
    val AVAILABLE: List<String> = listOf(
        "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi",
        "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro",
        "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na",
    )

    fun isValid(lang: String): Boolean = lang in AVAILABLE
}
