package com.genius.hyperlyrics.service.source

import com.genius.hyperlyrics.common.lyric.LrcParser
import com.genius.hyperlyrics.lyric.LrcLine

class MetadataLrcLyricSource : ServiceLyricSource {
    override val id = "lyric"
    override val displayName = "LRC"

    override suspend fun getLyrics(data: SyncData): List<LrcLine>? {
        val lyricRaw = data.lyricRaw
        if (lyricRaw.isNullOrBlank()) return null
        return try {
            LrcParser.parse(lyricRaw)
        } catch (_: Exception) {
            null
        }
    }
}
