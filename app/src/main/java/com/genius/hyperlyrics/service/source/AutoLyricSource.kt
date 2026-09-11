package com.genius.hyperlyrics.service.source

import com.genius.hyperlyrics.lyric.LrcLine

class AutoLyricSource(
    private val lyricInfoSource: ServiceLyricSource,
    private val lrcSource: ServiceLyricSource
) : ServiceLyricSource {
    override val id = "auto"
    override val displayName = "Auto"

    override suspend fun getLyrics(data: SyncData): List<LrcLine>? {
        val lyricInfoLines = lyricInfoSource.getLyrics(data)
        if (!lyricInfoLines.isNullOrEmpty()) {
            return lyricInfoLines
        }
        return lrcSource.getLyrics(data)
    }
}
