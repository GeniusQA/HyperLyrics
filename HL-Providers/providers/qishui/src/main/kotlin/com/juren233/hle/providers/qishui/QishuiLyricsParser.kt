/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

internal data class QishuiLyricPayload(
    val trackId: String,
    val type: String,
    val content: String,
    val translations: List<QishuiTranslationPayload>,
    val source: QishuiLyricSource = QishuiLyricSource.PC_API,
    val timeline: List<QishuiTimelineLine> = emptyList(),
)

internal enum class QishuiLyricSource {
    PC_API,
    SHARE_PAGE,
}

internal data class QishuiTranslationPayload(
    val language: String,
    val type: String,
    val content: String,
)

internal data class QishuiTimelineWord(
    val begin: Long,
    val end: Long,
    val text: String,
)

internal data class QishuiTimelineLine(
    val begin: Long,
    val end: Long,
    val text: String,
    val translation: String?,
    val words: List<QishuiTimelineWord>,
)

internal object QishuiLyricsParser {
    private val krcLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val krcWordRegex = Regex("""<(\d+),(\d+),[^>]*>([^<]*)""")
    private val lrcTimeRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(
        payload: QishuiLyricPayload,
        durationMs: Long = -1L,
    ): List<QishuiTimelineLine> {
        val primary = payload.timeline
            .asSequence()
            .filter { it.text.isNotBlank() }
            .filter { durationMs <= 0L || it.begin < durationMs }
            .map { line -> boundStructuredLine(line, durationMs) }
            .sortedBy(QishuiTimelineLine::begin)
            .toList()
            .takeIf(List<QishuiTimelineLine>::isNotEmpty)
            ?: when (payload.type.uppercase()) {
                "KRC" -> parseKrc(payload.content)
                "TEXT" -> parseText(payload.content)
                else -> parseLrc(payload.content)
            }
        if (primary.isEmpty()) return emptyList()

        val translations = payload.translations
            .asSequence()
            .mapIndexedNotNull { index, candidate ->
                val parsed = when (candidate.type.uppercase()) {
                    "KRC" -> parseKrc(candidate.content)
                    "TEXT" -> parseText(candidate.content)
                    else -> parseLrc(candidate.content)
                }.filter { it.text.isNotBlank() }
                parsed.takeIf(List<QishuiTimelineLine>::isNotEmpty)
                    ?.let { TranslationCandidate(candidate, index, it) }
            }
            .sortedWith(
                compareBy<TranslationCandidate> { languagePriority(it.payload.language) }
                    .thenBy { it.payload.language.lowercase() }
                    .thenBy { it.index },
            )
            .firstOrNull()

        val aligned = primary.map { line ->
            val translation = translations?.lines.orEmpty()
                .asSequence()
                .map { candidate -> candidate to kotlin.math.abs(candidate.begin - line.begin) }
                .filter { (_, distance) -> distance <= TRANSLATION_TOLERANCE_MS }
                .minByOrNull { (_, distance) -> distance }
                ?.first
                ?.text
                ?.takeIf { it != line.text }
            line.copy(translation = translation ?: line.translation)
        }
        return completeEnds(aligned, durationMs)
    }

    private fun boundStructuredLine(
        line: QishuiTimelineLine,
        durationMs: Long,
    ): QishuiTimelineLine {
        if (durationMs <= line.begin) return line
        val words = line.words
            .asSequence()
            .filter { it.begin < durationMs }
            .map { word ->
                word.copy(end = minOf(maxOf(word.end, word.begin), durationMs))
            }
            .toList()
        return line.copy(
            end = minOf(maxOf(line.end, line.begin), durationMs),
            words = words,
        )
    }

    private data class TranslationCandidate(
        val payload: QishuiTranslationPayload,
        val index: Int,
        val lines: List<QishuiTimelineLine>,
    )

    private fun languagePriority(language: String): Int {
        val normalized = language.trim().lowercase().replace('_', '-')
        return when {
            normalized == "cn" || normalized.startsWith("zh") -> 0
            normalized.startsWith("en") -> 1
            normalized.startsWith("ja") -> 2
            normalized.startsWith("ko") -> 3
            else -> 10
        }
    }

    private fun parseKrc(content: String): List<QishuiTimelineLine> = content.lineSequence()
        .mapNotNull { raw ->
            val match = krcLineRegex.matchEntire(raw.trim()) ?: return@mapNotNull null
            val begin = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val declaredDuration = match.groupValues[2].toLongOrNull() ?: 0L
            val body = match.groupValues[3]
            val words = krcWordRegex.findAll(body).mapNotNull { wordMatch ->
                val offset = wordMatch.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val duration = wordMatch.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val text = decodeEntities(wordMatch.groupValues[3])
                QishuiTimelineWord(
                    begin = begin + offset,
                    end = begin + offset + duration.coerceAtLeast(0L),
                    text = text,
                )
            }.toList()
            val text = if (words.isNotEmpty()) {
                words.joinToString(separator = "") { it.text }
            } else {
                decodeEntities(body.replace(krcWordRegex, ""))
            }.trim()
            if (text.isBlank()) return@mapNotNull null
            val wordEnd = words.maxOfOrNull(QishuiTimelineWord::end) ?: begin
            QishuiTimelineLine(
                begin = begin,
                end = maxOf(begin + declaredDuration.coerceAtLeast(0L), wordEnd),
                text = text,
                translation = null,
                words = words,
            )
        }
        .sortedBy(QishuiTimelineLine::begin)
        .toList()

    private fun parseLrc(content: String): List<QishuiTimelineLine> = content.lineSequence()
        .flatMap { raw ->
            val matches = lrcTimeRegex.findAll(raw).toList()
            if (matches.isEmpty()) return@flatMap emptySequence()
            val text = decodeEntities(lrcTimeRegex.replace(raw, "")).trim()
            if (text.isBlank()) return@flatMap emptySequence()
            matches.asSequence().mapNotNull { match ->
                val begin = lrcTimestamp(match) ?: return@mapNotNull null
                QishuiTimelineLine(
                    begin = begin,
                    end = begin,
                    text = text,
                    translation = null,
                    words = emptyList(),
                )
            }
        }
        .sortedBy(QishuiTimelineLine::begin)
        .distinctBy { it.begin to it.text }
        .toList()

    private fun parseText(content: String): List<QishuiTimelineLine> = content.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .mapIndexed { index, text ->
            val begin = index * TEXT_LINE_DURATION_MS
            QishuiTimelineLine(
                begin = begin,
                end = begin + TEXT_LINE_DURATION_MS,
                text = decodeEntities(text),
                translation = null,
                words = emptyList(),
            )
        }
        .toList()

    private fun completeEnds(
        lines: List<QishuiTimelineLine>,
        durationMs: Long,
    ): List<QishuiTimelineLine> = lines.mapIndexed { index, line ->
        val nextBegin = lines.getOrNull(index + 1)?.begin
        val inferredEnd = when {
            line.end > line.begin -> line.end
            nextBegin != null && nextBegin > line.begin -> nextBegin
            durationMs > line.begin -> durationMs
            else -> line.begin + TEXT_LINE_DURATION_MS
        }
        line.copy(end = maxOf(inferredEnd, line.words.maxOfOrNull(QishuiTimelineWord::end) ?: 0L))
    }

    private fun lrcTimestamp(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues[3]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun decodeEntities(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private const val TRANSLATION_TOLERANCE_MS = 10L
    private const val TEXT_LINE_DURATION_MS = 5_000L
}
