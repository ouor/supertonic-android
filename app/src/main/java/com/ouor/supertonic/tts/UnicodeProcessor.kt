package com.ouor.supertonic.tts

import java.text.Normalizer

/**
 * Converts input text into model token ids, faithfully mirroring the reference
 * `UnicodeProcessor` (py/helper.py, java/Helper.java).
 *
 * @param indexer maps a 16-bit unicode value -> token id (loaded from unicode_indexer.json,
 *                a flat JSON array of length 65536).
 */
class UnicodeProcessor(private val indexer: IntArray) {

    /** Result of tokenizing a batch of texts. */
    class Result(
        val textIds: Array<LongArray>,   // [bsz][maxLen]
        val textMask: Array<FloatArray>, // [bsz][maxLen]  (model wants [bsz][1][maxLen])
    )

    fun process(textList: List<String>, langList: List<String>): Result {
        val processed = textList.indices.map { preprocess(textList[it], langList[it]) }

        // Code-point counts (surrogate-pair aware), matching the reference.
        val codePoints = processed.map { it.codePoints().toArray() }
        val lengths = IntArray(codePoints.size) { codePoints[it].size }
        val maxLen = (lengths.maxOrNull() ?: 0)

        val textIds = Array(processed.size) { LongArray(maxLen) }
        for (i in codePoints.indices) {
            val cps = codePoints[i]
            for (j in cps.indices) {
                // Reference uses uint16 semantics for the index.
                textIds[i][j] = indexer[cps[j] and 0xFFFF].toLong()
            }
        }

        val textMask = Array(processed.size) { i ->
            FloatArray(maxLen) { j -> if (j < lengths[i]) 1f else 0f }
        }
        return Result(textIds, textMask)
    }

    private fun preprocess(input: String, lang: String): String {
        if (!Languages.isValid(lang)) {
            throw IllegalArgumentException("Invalid language: $lang. Available: ${Languages.AVAILABLE}")
        }

        var text = Normalizer.normalize(input, Normalizer.Form.NFKD)
        text = removeEmojis(text)

        for ((k, v) in REPLACEMENTS) text = text.replace(k, v)
        text = text.replace(Regex("[♥☆♡©\\\\]"), "")

        text = text.replace("@", " at ")
        text = text.replace("e.g.,", "for example, ")
        text = text.replace("i.e.,", "that is, ")

        // Fix spacing around punctuation.
        text = text.replace(" ,", ",")
            .replace(" .", ".")
            .replace(" !", "!")
            .replace(" ?", "?")
            .replace(" ;", ";")
            .replace(" :", ":")
            .replace(" '", "'")

        while (text.contains("\"\"")) text = text.replace("\"\"", "\"")
        while (text.contains("''")) text = text.replace("''", "'")
        while (text.contains("``")) text = text.replace("``", "`")

        text = text.replace(Regex("\\s+"), " ").trim()

        if (!text.matches(END_PUNCT_REGEX)) text += "."

        return "<$lang>$text</$lang>"
    }

    private fun removeEmojis(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (!isEmoji(cp)) sb.appendCodePoint(cp)
            i += charCount
        }
        return sb.toString()
    }

    private fun isEmoji(cp: Int): Boolean =
        (cp in 0x1F600..0x1F64F) ||
        (cp in 0x1F300..0x1F5FF) ||
        (cp in 0x1F680..0x1F6FF) ||
        (cp in 0x1F700..0x1F77F) ||
        (cp in 0x1F780..0x1F7FF) ||
        (cp in 0x1F800..0x1F8FF) ||
        (cp in 0x1F900..0x1F9FF) ||
        (cp in 0x1FA00..0x1FA6F) ||
        (cp in 0x1FA70..0x1FAFF) ||
        (cp in 0x2600..0x26FF) ||
        (cp in 0x2700..0x27BF) ||
        (cp in 0x1F1E6..0x1F1FF)

    companion object {
        private val REPLACEMENTS: Map<String, String> = linkedMapOf(
            "–" to "-",  // en dash
            "‑" to "-",  // non-breaking hyphen
            "—" to "-",  // em dash
            "_" to " ",
            "“" to "\"", // left double quote
            "”" to "\"", // right double quote
            "‘" to "'",  // left single quote
            "’" to "'",  // right single quote
            "´" to "'",  // acute accent
            "`" to "'",       // grave accent
            "[" to " ",
            "]" to " ",
            "|" to " ",
            "/" to " ",
            "#" to " ",
            "→" to " ",  // right arrow
            "←" to " ",  // left arrow
        )

        private val END_PUNCT_REGEX =
            Regex(".*[.!?;:,'\"“”‘’)\\]}…。」』】〉》›»]$", RegexOption.DOT_MATCHES_ALL)
    }
}
