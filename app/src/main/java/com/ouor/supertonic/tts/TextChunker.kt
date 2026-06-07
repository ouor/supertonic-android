package com.ouor.supertonic.tts

import java.util.regex.Pattern

/**
 * Splits long input into model-sized chunks by paragraph, then sentence, then
 * comma/word as needed. Mirrors `Helper.chunkText` (java/Helper.java).
 */
object TextChunker {

    private const val MAX_CHUNK_LENGTH = 300

    private val ABBREVIATIONS = arrayOf(
        "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.",
        "St.", "Ave.", "Rd.", "Blvd.", "Dept.", "Inc.", "Ltd.",
        "Co.", "Corp.", "etc.", "vs.", "i.e.", "e.g.", "Ph.D.",
    )

    private val SENTENCE_PATTERN: Pattern by lazy {
        val abbrev = ABBREVIATIONS.joinToString("|") { Pattern.quote(it) }
        Pattern.compile("(?<!(?:$abbrev))(?<=[.!?])\\s+")
    }

    fun chunk(text: String, maxLen: Int = MAX_CHUNK_LENGTH): List<String> {
        val limit = if (maxLen == 0) MAX_CHUNK_LENGTH else maxLen
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return listOf("")

        val paragraphs = trimmed.split(Regex("\\n\\s*\\n"))
        val chunks = mutableListOf<String>()

        for (raw in paragraphs) {
            val para = raw.trim()
            if (para.isEmpty()) continue
            if (para.length <= limit) {
                chunks.add(para)
                continue
            }

            val sentences = SENTENCE_PATTERN.split(para)
            val current = StringBuilder()

            for (rawSentence in sentences) {
                val sentence = rawSentence.trim()
                if (sentence.isEmpty()) continue

                if (sentence.length > limit) {
                    if (current.isNotEmpty()) {
                        chunks.add(current.toString().trim())
                        current.setLength(0)
                    }
                    splitOversizedSentence(sentence, limit, current, chunks)
                    continue
                }

                if (current.length + sentence.length + 1 > limit && current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append(" ")
                current.append(sentence)
            }

            if (current.isNotEmpty()) chunks.add(current.toString().trim())
        }

        return if (chunks.isEmpty()) listOf("") else chunks
    }

    private fun splitOversizedSentence(
        sentence: String,
        limit: Int,
        current: StringBuilder,
        chunks: MutableList<String>,
    ) {
        for (rawPart in sentence.split(",")) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue

            if (part.length > limit) {
                val wordChunk = StringBuilder()
                for (word in part.split(Regex("\\s+"))) {
                    if (wordChunk.length + word.length + 1 > limit && wordChunk.isNotEmpty()) {
                        chunks.add(wordChunk.toString().trim())
                        wordChunk.setLength(0)
                    }
                    if (wordChunk.isNotEmpty()) wordChunk.append(" ")
                    wordChunk.append(word)
                }
                if (wordChunk.isNotEmpty()) chunks.add(wordChunk.toString().trim())
            } else {
                if (current.length + part.length + 1 > limit && current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append(", ")
                current.append(part)
            }
        }
    }

    /** Reference rule: Korean/Japanese chunk at 120 chars, everything else at 300. */
    fun maxLenForLang(lang: String): Int = if (lang == "ko" || lang == "ja") 120 else 300
}
