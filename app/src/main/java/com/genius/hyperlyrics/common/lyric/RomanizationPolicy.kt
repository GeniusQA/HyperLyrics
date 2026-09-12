package com.genius.hyperlyrics.common.lyric

import java.util.Locale

object RomanizationPolicy {
    private val htmlTag = Regex("<[^>]*>")
    private val htmlEntity = Regex("&(?:#\\d+|#x[0-9a-fA-F]+|[A-Za-z]+);")

    fun sanitize(originalText: String?, pronunciation: String?): String? {
        val candidate = pronunciation?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val visibleCandidate = visibleText(candidate)
        if (visibleCandidate.isEmpty() || !isLatinRomanization(visibleCandidate)) return null

        val visibleOriginal = visibleText(originalText.orEmpty())
        if (!hasNonLatinLetter(visibleOriginal)) return null

        val normalizedOriginal = comparableText(visibleOriginal)
        val normalizedCandidate = comparableText(visibleCandidate)
        if (normalizedCandidate.isEmpty() || normalizedCandidate == normalizedOriginal) return null
        return candidate
    }

    fun isLatinLanguageTag(language: String?): Boolean =
        language
            ?.trim()
            ?.split('-')
            ?.any { it.equals("Latn", ignoreCase = true) } == true

    /**
     * 判断一段文本是否看起来像拉丁罗马音（仅含拉丁字母，且至少有一个字母）。
     * 用于 LRCLIB 等兜底歌词的自动罗马音检测。
     */
    fun looksLikeRomanization(text: String?): Boolean {
        val visible = visibleText(text ?: return false)
        if (visible.isEmpty()) return false
        return isLatinRomanization(visible)
    }

    private fun isLatinRomanization(text: String): Boolean {
        var hasLatinLetter = false
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetter(codePoint)) {
                if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN) {
                    return false
                }
                hasLatinLetter = true
            }
            index += Character.charCount(codePoint)
        }
        return hasLatinLetter
    }

    private fun hasNonLatinLetter(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (
                Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun visibleText(text: String): String = text
        .replace(htmlTag, " ")
        .replace(htmlEntity, " ")
        .trim()

    private fun comparableText(text: String): String {
        val normalized = text.lowercase(Locale.ROOT)
        return buildString(normalized.length) {
            var index = 0
            while (index < normalized.length) {
                val codePoint = normalized.codePointAt(index)
                if (Character.isLetterOrDigit(codePoint)) appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
    }
}
