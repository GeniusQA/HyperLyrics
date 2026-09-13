/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal data class SpotifySyllable(
    val startMs: Long,
    val charCount: Int,
)

internal data class SpotifyApiLyricLine(
    val startMs: Long,
    val text: String,
    val syllables: List<SpotifySyllable>,
)

internal data class SpotifyTranslation(
    val language: String,
    val lines: List<String>,
)

internal data class SpotifyLyricsPayload(
    val trackUri: String,
    val syncType: Int,
    val language: String,
    val lines: List<SpotifyApiLyricLine>,
    val translations: List<SpotifyTranslation>,
)

internal data class SpotifyTimelineWord(
    val begin: Long,
    val end: Long,
    val text: String,
)

internal data class SpotifyTimelineLine(
    val begin: Long,
    val end: Long,
    val text: String,
    val words: List<SpotifyTimelineWord>,
)

internal object SpotifyLyricsPayloadExtractor {
    fun extract(trackKey: Any?, result: Any?): SpotifyLyricsPayload? {
        result ?: return null
        val trackUri = (trackKey as? String)?.trim()
            ?.takeIf { it.startsWith("spotify:track:") }
            ?: return null
        val lyricsModel = result.readField("a") ?: result.instanceFields()
            .mapNotNull { it.read(result) }
            .firstOrNull(::looksLikeLyricsModel)
            ?: return null
        val rawLines = lyricsModel.readField("a").asObjectList()
            .ifEmpty { lyricsModel.findStructuredList(::looksLikeLyricLine) }
        if (rawLines.isEmpty()) return null
        val lines = rawLines.mapNotNull(::extractLine)
        if (lines.isEmpty()) return null
        val rawTranslations = lyricsModel.readField("c").asObjectList()
            .ifEmpty { lyricsModel.findStructuredList(::looksLikeTranslation) }
        val translations = rawTranslations.mapNotNull(::extractTranslation)
        return SpotifyLyricsPayload(
            trackUri = trackUri,
            syncType = (lyricsModel.readField("b") as? Number)?.toInt() ?: 1,
            language = lyricsModel.readField("d")?.toString().orEmpty(),
            lines = lines,
            translations = translations,
        )
    }

    private fun extractLine(value: Any): SpotifyApiLyricLine? {
        val start = (value.readField("a") as? Number)?.toLong()
            ?: value.numberFields().singleOrNull()?.toLong()
            ?: return null
        val text = value.readField("b")?.toString()
            ?: value.stringFields().singleOrNull()
            ?: return null
        val rawSyllables = value.readField("c").asObjectList()
            .ifEmpty { value.findStructuredList(::looksLikeSyllable) }
        val syllables = rawSyllables.mapNotNull { syllable ->
            val startMs = (syllable.readField("a") as? Number)?.toLong()
                ?: syllable.numberFields().getOrNull(0)?.toLong()
                ?: return@mapNotNull null
            val charCount = (syllable.readField("b") as? Number)?.toInt()
                ?: syllable.numberFields().getOrNull(1)?.toInt()
                ?: return@mapNotNull null
            SpotifySyllable(startMs = startMs, charCount = charCount.coerceAtLeast(0))
        }
        return SpotifyApiLyricLine(startMs = start, text = text, syllables = syllables)
    }

    private fun extractTranslation(value: Any): SpotifyTranslation? {
        val language = value.readField("a")?.toString()
            ?: value.stringFields().singleOrNull()
            ?: return null
        val lines = value.readField("b").asObjectList().map(Any::toString)
            .ifEmpty {
                value.instanceFields()
                    .mapNotNull { it.read(value).asObjectList().takeIf(List<Any>::isNotEmpty) }
                    .firstOrNull()
                    ?.map(Any::toString)
                    .orEmpty()
            }
        return SpotifyTranslation(language = language, lines = lines)
    }

    private fun looksLikeLyricsModel(value: Any): Boolean = value.instanceFields().any { field ->
        field.read(value).asObjectList().firstOrNull()?.let(::looksLikeLyricLine) == true
    }

    private fun looksLikeLyricLine(value: Any): Boolean =
        value.numberFields().size == 1 && value.stringFields().size == 1

    private fun looksLikeSyllable(value: Any): Boolean = value.numberFields().size >= 2

    private fun looksLikeTranslation(value: Any): Boolean =
        value.stringFields().size == 1 && value.instanceFields().any { it.read(value).asObjectList().isNotEmpty() }
}

internal object SpotifyLyricsSuccessEventExtractor {
    /** 按原始构造参数顺序提取 track URI 与已解析歌词。 */
    fun extract(arguments: Array<Any?>): SpotifyLyricsPayload? =
        SpotifyLyricsPayloadExtractor.extract(
            trackKey = arguments.getOrNull(0),
            result = arguments.getOrNull(1),
        )
}

internal object SpotifyLyricsTimelineMapper {
    fun map(
        payload: SpotifyLyricsPayload,
        durationMs: Long = -1L,
    ): List<SpotifyTimelineLine> = payload.lines.mapIndexed { index, line ->
        val nextBegin = payload.lines.getOrNull(index + 1)?.startMs
        val end = when {
            nextBegin != null && nextBegin > line.startMs -> nextBegin
            durationMs > line.startMs -> durationMs
            else -> line.startMs + DEFAULT_LINE_DURATION_MS
        }
        // Spotify 自带翻译刻意不发布：translation 槽位必须留给模块的三方在线
        // 翻译源，模块只按行补空槽，Provider 先占位会让在线翻译补不进去。
        SpotifyTimelineLine(
            begin = line.startMs,
            end = end,
            text = line.text,
            words = mapSyllables(line, end),
        )
    }

    private fun mapSyllables(
        line: SpotifyApiLyricLine,
        lineEnd: Long,
    ): List<SpotifyTimelineWord> {
        if (line.syllables.isEmpty()) return emptyList()
        var textOffset = 0
        val resolved = line.syllables.map { syllable ->
            val begin = syllable.startMs.takeIf { it >= line.startMs }
                ?: (line.startMs + syllable.startMs.coerceAtLeast(0L))
            val endOffset = (textOffset + syllable.charCount).coerceAtMost(line.text.length)
            val text = line.text.substring(textOffset, endOffset)
            textOffset = endOffset
            begin to text
        }
        return resolved.mapIndexedNotNull { index, (begin, text) ->
            if (text.isEmpty()) return@mapIndexedNotNull null
            val end = resolved.getOrNull(index + 1)?.first
                ?.takeIf { it >= begin }
                ?: lineEnd.coerceAtLeast(begin)
            SpotifyTimelineWord(begin = begin, end = end, text = text)
        }
    }

    private const val DEFAULT_LINE_DURATION_MS = 5_000L
}

private fun Any?.asObjectList(): List<Any> = when (this) {
    is Iterable<*> -> mapNotNull { it }
    is Array<*> -> mapNotNull { it }
    else -> emptyList()
}

private fun Any.findStructuredList(predicate: (Any) -> Boolean): List<Any> = instanceFields()
    .map { it.read(this).asObjectList() }
    .firstOrNull { values -> values.firstOrNull()?.let(predicate) == true }
    .orEmpty()

private fun Any.numberFields(): List<Number> = instanceFields()
    .mapNotNull { it.read(this) as? Number }

private fun Any.stringFields(): List<String> = instanceFields()
    .mapNotNull { it.read(this) as? String }

private fun Any.readField(name: String): Any? = instanceFields()
    .firstOrNull { it.name == name }
    ?.read(this)

private fun Any.instanceFields(): List<Field> = generateSequence(javaClass) { it.superclass }
    .flatMap { it.declaredFields.asSequence() }
    .filterNot { Modifier.isStatic(it.modifiers) }
    .onEach { it.isAccessible = true }
    .toList()

private fun Field.read(receiver: Any): Any? = runCatching { get(receiver) }.getOrNull()
