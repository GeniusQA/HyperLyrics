/*
 * Portions of the QQ QRC parser are derived from LyricProvider
 * (Apache License 2.0, Copyright 2026 Proify, Tomakino).
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic.qrc

import io.github.proify.lyricon.lyric.model.LyricWord

internal data class QqQrcLine(
    val begin: Long,
    val end: Long,
    val text: String,
    val words: List<LyricWord>,
)

/** Parses decrypted QQ QRC XML or its plain QRC body. */
internal object QqQrcParser {
    private val linePattern = Regex("""\[(\d+)\s*,\s*(\d+)]""")
    private val wordPattern = Regex("""([^()\n\r]*)\((\d+)\s*,\s*(\d+)\)""")
    private val lyricContentPattern = Regex("""LyricContent\s*=\s*"([\s\S]*?)"(?=\s*/?>)""")

    fun parse(raw: String?): List<QqQrcLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val content = lyricContentPattern.find(raw)?.groupValues?.getOrNull(1)
            ?.let(::decodeXmlEntities)
            ?: raw
        return parseBody(content)
    }

    private fun parseBody(content: String): List<QqQrcLine> {
        val lineMatches = linePattern.findAll(content).toList()
        if (lineMatches.isEmpty()) return emptyList()

        return lineMatches.mapIndexedNotNull { index, match ->
            val begin = match.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: 0L
            val bodyStart = match.range.last + 1
            val bodyEnd = lineMatches.getOrNull(index + 1)?.range?.first
                ?: content.lastIndexOf(']').takeIf { it > bodyStart }
                ?: content.length
            if (bodyStart >= bodyEnd) return@mapIndexedNotNull null

            val body = content.substring(bodyStart, bodyEnd).trim('\n', '\r')
            val words = wordPattern.findAll(body).mapNotNull { word ->
                val text = word.groupValues[1]
                val wordBegin = word.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val wordDuration = word.groupValues[3].toLongOrNull() ?: return@mapNotNull null
                if (text.isEmpty()) return@mapNotNull null
                LyricWord().apply {
                    this.begin = wordBegin
                    this.end = wordBegin + wordDuration
                    this.duration = wordDuration
                    this.text = text
                }
            }.toList()
            val text = if (words.isEmpty()) {
                body.replace(Regex("""\(\d+\s*,\s*\d+\)"""), "").trim()
            } else {
                words.joinToString("") { it.text.orEmpty() }
            }
            QqQrcLine(begin, begin + duration, text, words)
        }.sortedBy(QqQrcLine::begin)
    }

    private fun decodeXmlEntities(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&apos;", "'")
}
