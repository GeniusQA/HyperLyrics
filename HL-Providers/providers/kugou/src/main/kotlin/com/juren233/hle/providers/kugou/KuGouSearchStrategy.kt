/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

/**
 * 酷狗歌词搜索阶梯。
 *
 * 完整标题关键词始终优先；只有当该请求成功但没有任何可用候选
 * （0 候选或全部低于 MINIMUM_SCORE）时，才用去掉括号段的标题变体重试，
 * 用于媒体会话不提供 mediaId、只能纯关键词搜索时的服务器召回补偿
 * （实测：双语标题完整关键词 0 候选，去括号后可命中库存的完整双语标题）。
 *
 * 去括号不需要判断括号是否属于真实歌名：它只改变查询词，不改变采纳标准——
 * [KuGouCandidateSelector] 仍按完整原始标题、歌手与时长打分（≥70 分），
 * 完整标题能命中的歌曲永远走不到变体；发布的歌名/歌手也永远来自
 * MediaSession 元数据（见 [KuGouPluginEntry] 的 `placeholder`/`toSong`）。
 *
 * 返回的是首个有关格候选关键词下的完整降序序列：首选之外仍是同一门槛
 * 筛出的顺位候选，仅供主候选缺翻译段时的同门槛顺位兜底，绝不放宽匹配。
 */
internal object KuGouSearchStrategy {
    fun rank(
        track: KuGouTrackMetadata,
        fetch: (keyword: String) -> List<KuGouSearchCandidate>,
    ): List<KuGouSearchCandidate> {
        if (!track.isSearchable) return emptyList()
        KuGouCandidateSelector.rank(track, fetch(track.keyword()))
            .takeIf(List<KuGouSearchCandidate>::isNotEmpty)
            ?.let { return it }
        for (keyword in KuGouKeywordVariants.fallbackKeywords(track)) {
            KuGouCandidateSelector.rank(track, fetch(keyword))
                .takeIf(List<KuGouSearchCandidate>::isNotEmpty)
                ?.let { return it }
        }
        return emptyList()
    }
}

internal object KuGouKeywordVariants {
    // 失败歌曲的额外请求封顶：总请求数最多 1 + MAX_VARIANTS。
    private const val MAX_VARIANTS = 3
    private val bracketedSegment = Regex(
        "\\s*[（(\\[［【「『〔][^（()）\\[\\]［］【】「」『』〔〕]*[）)\\]］】」』〕]",
    )
    private val whitespace = Regex("\\s+")

    fun fallbackKeywords(track: KuGouTrackMetadata): List<String> {
        val title = track.title?.trim().orEmpty()
        if (title.isEmpty()) return emptyList()
        val segments = bracketedSegment.findAll(title).toList()
        if (segments.isEmpty()) return emptyList()
        val base = track.keyword()
        val variants = LinkedHashSet<String>()
        // 注释、翻译、版本后缀通常靠右，因此从最右段开始逐个累计去掉；
        // 删除区间基于原始标题，必须从右往左删，避免前面的删除使区间位移。
        for (count in 1..segments.size) {
            if (variants.size >= MAX_VARIANTS) break
            var reduced = title
            for (segment in segments.asReversed().take(count)) {
                reduced = reduced.removeRange(segment.range.first, segment.range.last + 1)
            }
            val keyword = buildKeyword(track.artist, reduced.replace(whitespace, " ").trim())
            if (keyword.isNotEmpty() && keyword != base) variants += keyword
        }
        return variants.toList().take(MAX_VARIANTS)
    }

    // 与 KuGouTrackMetadata.keyword() 保持同一形态，只是标题换成去括号后的部分。
    private fun buildKeyword(artist: String?, title: String): String = when {
        !artist.isNullOrBlank() -> "$artist - $title"
        else -> title
    }.take(200)
}
