package com.genius.hyperlyrics.root.aitrans

import com.genius.hyperlyrics.common.extensions.md5
import com.genius.hyperlyrics.lyric.model.Song
import com.genius.hyperlyrics.lyric.style.AiTranslationConfigs

internal object AITranslationKey {
    fun calculate(configs: AiTranslationConfigs, song: Song, lines: List<String>): String {
        return buildString {
            append("target=").appendLine(configs.targetLanguage.orEmpty())
            append("title=").appendLine(song.name.orEmpty())
            append("artist=").appendLine(song.artist.orEmpty())
            lines.forEachIndexed { index, line ->
                append(index).append(':').appendLine(line)
            }
        }.md5()
    }
}



