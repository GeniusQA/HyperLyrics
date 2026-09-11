package com.genius.hyperlyrics.service.source

import com.genius.hyperlyrics.common.lyric.LyricInfoParser
import com.genius.hyperlyrics.lyric.LrcLine

class LyricInfoLyricSource : ServiceLyricSource {
    override val id = "lyricinfo"
    override val displayName = "LyricInfo"

    override suspend fun getLyrics(data: SyncData): List<LrcLine>? {
        val lyricInfo = data.lyricInfoRaw
        if (lyricInfo.isNullOrBlank()) return null
        return try {
            val song = LyricInfoParser.parse(lyricInfo, data.identityTitle, data.identityArtist)
            song?.lyrics?.map { LrcLine(it.begin, it.text ?: "") }
        } catch (_: Exception) {
            null
        }
    }
}
