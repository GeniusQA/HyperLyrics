package com.genius.hyperlyrics.service.source

import com.genius.hyperlyrics.lyric.LrcLine

class TitleLyricSource : ServiceLyricSource {
    override val id = "title"
    override val displayName = "Title"

    override suspend fun getLyrics(data: SyncData): List<LrcLine>? {
        // 标题模式不提供滚动歌词，返回 null 供调度器自动走静态标题匹配逻辑
        return null
    }
}
