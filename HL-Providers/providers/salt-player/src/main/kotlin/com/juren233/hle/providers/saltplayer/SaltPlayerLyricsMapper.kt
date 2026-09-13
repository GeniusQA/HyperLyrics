/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

internal data class SaltPlayerTrackMetadata(
    val id: String? = null,
    val mediaUri: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
) {
    val identity: String?
        get() = id?.trim()?.takeIf(String::isNotEmpty)
            ?: listOf(title, artist)
                .map { it?.trim().orEmpty() }
                .takeIf { values -> values.any(String::isNotEmpty) }
                ?.joinToString("|")

    val localLyricsRequestKey: String?
        get() = identity?.let {
            listOf(
                it,
                mediaUri.orEmpty(),
                album.orEmpty(),
                durationMs.coerceAtLeast(0L).toString(),
            ).joinToString("|")
        }
}

internal data class SaltPlayerLyricsDocument(
    val lines: List<SaltPlayerLyricsLine>,
)

internal data class SaltPlayerLyricsLine(
    val beginMs: Long,
    val endMs: Long,
    val mainText: String,
    val translation: String?,
    val cells: List<SaltPlayerLyricsCell>,
)

internal data class SaltPlayerLyricsCell(
    val beginMs: Long,
    val endMs: Long,
    val text: String,
)

internal object SaltPlayerLyricsMapper {
    fun map(
        document: SaltPlayerLyricsDocument,
        metadata: SaltPlayerTrackMetadata,
    ): Song = Song().apply {
        id = metadata.id?.trim()?.takeIf(String::isNotEmpty)
        name = metadata.title?.trim()?.takeIf(String::isNotEmpty)
        artist = metadata.artist?.trim()?.takeIf(String::isNotEmpty)
        val mappedLines = document.lines
            .sortedWith(compareBy(SaltPlayerLyricsLine::beginMs, SaltPlayerLyricsLine::endMs))
            .map(::mapLine)
        duration = metadata.durationMs.takeIf { it > 0L }
            ?: mappedLines.maxOfOrNull { it.end }
            ?: 0L
        lyrics = mappedLines.takeIf { it.isNotEmpty() }
    }

    fun placeholder(metadata: SaltPlayerTrackMetadata): Song = Song().apply {
        id = metadata.id?.trim()?.takeIf(String::isNotEmpty)
        name = metadata.title?.trim()?.takeIf(String::isNotEmpty)
        artist = metadata.artist?.trim()?.takeIf(String::isNotEmpty)
        duration = metadata.durationMs.coerceAtLeast(0L)
    }

    private fun mapLine(line: SaltPlayerLyricsLine): RichLyricLine = RichLyricLine().apply {
        begin = line.beginMs
        end = line.endMs
        duration = (line.endMs - line.beginMs).coerceAtLeast(0L)
        text = line.mainText
        translation = line.translation?.takeUnless { it.trim() == line.mainText.trim() }
        words = mapWords(line)
    }

    private fun mapWords(line: SaltPlayerLyricsLine): List<LyricWord> {
        val lineEnd = line.endMs.coerceAtLeast(line.beginMs)
        val exactWords = line.cells.mapNotNull { cell ->
            val begin = cell.beginMs.coerceAtLeast(line.beginMs)
            val end = cell.endMs.coerceAtMost(lineEnd)
            if (end <= begin) return@mapNotNull null
            cell.text.takeIf(String::isNotEmpty)?.let { text ->
                LyricWord().apply {
                    this.begin = begin
                    this.end = end
                    duration = (end - begin).coerceAtLeast(0L)
                    this.text = text
                }
            }
        }
        if (exactWords.isNotEmpty()) return exactWords
        return listOf(
            LyricWord().apply {
                begin = line.beginMs
                end = line.endMs
                duration = (line.endMs - line.beginMs).coerceAtLeast(0L)
                text = line.mainText
            },
        )
    }
}
