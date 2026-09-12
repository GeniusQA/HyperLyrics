package com.genius.hyperlyrics.online

import android.content.Context
import com.genius.hyperlyrics.common.lyric.AppleMissingLyricsSourceStatus
import com.genius.hyperlyrics.common.lyric.RomanizationPolicy
import com.genius.hyperlyrics.common.lyric.OnlineTranslationContentPolicy
import com.genius.hyperlyrics.lyric.LrcLine
import com.genius.hyperlyrics.online.model.LyricsLine
import com.genius.hyperlyrics.online.model.LyricsResult
import com.genius.hyperlyrics.online.model.SearchSource
import com.genius.hyperlyrics.online.model.SongSearchResult
import com.genius.hyperlyrics.online.model.Source
import com.genius.hyperlyrics.online.utils.ChineseUtils
import com.genius.hyperlyrics.utils.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import kotlin.math.abs

object OnlineLyricTargeter {
    private const val TIMEOUT_MS = 5000L

    /** 候选达标阈值：达到该分数且成功取到歌词即视为命中（平分时按来源优先级取先者）。 */
    const val PASS_SCORE = 85
    private const val NEAR_MISS_MIN_SCORE = 50
    private const val STRONG_DURATION_TOLERANCE_MS = 1_500L

    /**
     * 低于 [PASS_SCORE] 但仍可能由强身份（标题精确相等 + 时长接近）
     * 和歌词重叠校验放行的候选。歌词已在此处抓取，由调用方做最终验证。
     */
    data class NearMissCandidate(
        val score: Int,
        val lines: List<LrcLine>,
        val durationVerified: Boolean,
    )

    data class FetchOutcome(
        val lines: List<LrcLine>? = null,
        val wordLines: List<LyricsLine>? = null,
        val nearMiss: NearMissCandidate? = null,
        val sourceStatuses: List<AppleMissingLyricsSourceStatus> = emptyList(),
        val selectedSource: Source? = null,
        val rawAppleTtml: String? = null,
        val sourceLyricId: String? = null,
        val sourceLyricSha256: String? = null,
    )

    suspend fun fetchBestLyric(
        context: Context,
        pkgName: String,
        title: String,
        artist: String,
        durationMs: Long,
        originalTitle: String? = null,
        originalArtist: String? = null,
        preferOriginalMetadata: Boolean = false,
        preferredSource: Source? = null,
        requireTranslation: Boolean = false,
        fallbackToOtherSources: Boolean = true,
        sourceOrder: List<Source>? = null,
        statusSourceOrder: List<Source>? = null,
        album: String? = null,
        originalAlbum: String? = null,
        collectSourceStatuses: Boolean = false,
    ): List<LrcLine>? = fetchBestLyricWithNearMiss(
        context = context,
        pkgName = pkgName,
        title = title,
        artist = artist,
        durationMs = durationMs,
        originalTitle = originalTitle,
        originalArtist = originalArtist,
        preferOriginalMetadata = preferOriginalMetadata,
        preferredSource = preferredSource,
        requireTranslation = requireTranslation,
        fallbackToOtherSources = fallbackToOtherSources,
        sourceOrder = sourceOrder,
        statusSourceOrder = statusSourceOrder,
        album = album,
        originalAlbum = originalAlbum,
        collectSourceStatuses = collectSourceStatuses,
    ).lines

    suspend fun fetchBestLyricWithNearMiss(
        context: Context,
        pkgName: String,
        title: String,
        artist: String,
        durationMs: Long,
        originalTitle: String? = null,
        originalArtist: String? = null,
        preferOriginalMetadata: Boolean = false,
        preferredSource: Source? = null,
        requireTranslation: Boolean = false,
        fallbackToOtherSources: Boolean = true,
        sourceOrder: List<Source>? = null,
        statusSourceOrder: List<Source>? = null,
        album: String? = null,
        originalAlbum: String? = null,
        collectSourceStatuses: Boolean = false,
    ): FetchOutcome {
        // 搜索前净化脏标题（MV 尾缀/广告角标/重复分段），原始标题保留为原数据回退。
        val sanitized = sanitizeLyricSearchTitle(title, artist)
        val searchTitle = sanitized.value
        val searchArtist = artist.trim()
        // 仅当剥离了 MV 视频尾缀时媒体时长（视频长度）不可信，不参与计分；
        // 仅去除歌手/专辑分段时时长仍可信，保留计分。
        val effectiveDurationMs = if (sanitized.mvStripped) 0L else durationMs
        val outcome = performSearch(
            context = context,
            pkgName = pkgName,
            title = searchTitle,
            artist = searchArtist,
            durationMs = effectiveDurationMs,
            originalTitle = originalTitle ?: title.takeIf { it != searchTitle },
            originalArtist = originalArtist ?: artist.takeIf { it != searchArtist },
            preferOriginalMetadata = preferOriginalMetadata,
            preferredSource = preferredSource,
            requireTranslation = requireTranslation,
            fallbackToOtherSources = fallbackToOtherSources,
            sourceOrder = sourceOrder,
            statusSourceOrder = statusSourceOrder,
            album = album,
            originalAlbum = originalAlbum,
            collectSourceStatuses = collectSourceStatuses,
        )
        if (outcome.lines != null) {
            return FetchOutcome(
                lines = outcome.lines,
                wordLines = outcome.wordLines,
                sourceStatuses = outcome.sourceStatuses,
                selectedSource = outcome.selectedSource,
            )
        }
        val nearMiss = outcome.nearMiss ?: return FetchOutcome(
            sourceStatuses = outcome.sourceStatuses,
        )
        val lyricsResult = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                nearMiss.source.getLyrics(nearMiss.song)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w(
                    "OnlineTargeter",
                    "获取近失候选歌词异常: 源=${nearMiss.source.javaClass.simpleName}, ${e.message}",
                )
                null
            }
        }
        if (lyricsResult == null ||
            (lyricsResult.original.isEmpty() && lyricsResult.translated.isNullOrEmpty())
        ) {
            return FetchOutcome(sourceStatuses = outcome.sourceStatuses)
        }
        val lines = toLrcLines(lyricsResult)
        if (lines.isEmpty()) return FetchOutcome()
        if (requireTranslation && lines.none {
                OnlineTranslationContentPolicy.isMeaningful(it.translation)
            }
        ) {
            return FetchOutcome(sourceStatuses = outcome.sourceStatuses)
        }
        val wordLines = toWordLines(lyricsResult)
        LogManager.d(
            "OnlineTargeter",
            "近失候选歌词: 源=${nearMiss.source.javaClass.simpleName}, " +
                "得分=${nearMiss.score}, 行数=${lines.size}, " +
                "时长已校验=${nearMiss.durationVerified}",
        )
        return FetchOutcome(
            lines = lines,
            wordLines = wordLines,
            nearMiss = NearMissCandidate(
                score = nearMiss.score,
                lines = lines,
                durationVerified = nearMiss.durationVerified,
            ),
            sourceStatuses = mergeStatuses(
                outcome.sourceStatuses,
                statusFor(nearMiss.source.sourceType, lyricsResult, lines),
            ),
            selectedSource = nearMiss.source.sourceType,
        )
    }

    private suspend fun performSearch(
        context: Context,
        pkgName: String,
        title: String,
        artist: String,
        durationMs: Long,
        originalTitle: String?,
        originalArtist: String?,
        preferOriginalMetadata: Boolean,
        preferredSource: Source?,
        requireTranslation: Boolean,
        fallbackToOtherSources: Boolean,
        sourceOrder: List<Source>?,
        statusSourceOrder: List<Source>?,
        album: String?,
        originalAlbum: String?,
        collectSourceStatuses: Boolean,
    ): SearchOutcome {
        val ne = LyricApiProvider.getNeSource(context)
        val qm = LyricApiProvider.qmSource
        val sourcesByType = mapOf(
            Source.NE to ne,
            Source.QM to qm,
            Source.KUWO to LyricApiProvider.kuwoSource,
            Source.KUGOU to LyricApiProvider.kugouSource,
            Source.LRCLIB to LyricApiProvider.lrclibSource,
        )
        val resolvedSourceOrder = sourceOrder?.distinct().orEmpty().ifEmpty {
            resolveSourceOrder(
                pkgName = pkgName,
                preferredSource = preferredSource,
                fallbackToOtherSources = fallbackToOtherSources,
            )
        }
        // LRCLIB 不属于在线源（在线源只有 NE/QM/KUWO/KUGOU 四个平台），
        // 仅在调用方显式传入 Source.LRCLIB（无 Provider 播放器的通用歌词源 Provider）时使用。
        val sources = resolvedSourceOrder.mapNotNull(sourcesByType::get)
        val searchedSourceTypes = resolvedSourceOrder.toSet()
        val statusOnlySources = statusSourceOrder
            ?.distinct()
            .orEmpty()
            .filterNot(searchedSourceTypes::contains)
            .mapNotNull(sourcesByType::get)

        val resolvedTitle = originalTitle?.takeIf { it.isNotBlank() } ?: title
        val resolvedArtist = originalArtist?.takeIf { it.isNotBlank() } ?: artist
        val hasDistinctOriginalMetadata = shouldRetryWithOriginalMetadata(
            title,
            artist,
            originalTitle,
            originalArtist,
        )
        val searches = resolveMetadataSearchOrder(
            preferOriginalMetadata = preferOriginalMetadata,
            hasDistinctOriginalMetadata = hasDistinctOriginalMetadata,
        ).map { useOriginalMetadata ->
            if (useOriginalMetadata) {
                SearchMetadata(resolvedTitle, resolvedArtist, "Apple 内部原名")
            } else {
                SearchMetadata(title, artist, "当前元数据")
            }
        }
        var bestNearMiss: NearMiss? = null
        val allSourceStatuses = linkedMapOf<Source, AppleMissingLyricsSourceStatus>()
        searches.forEachIndexed { index, metadata ->
            if (index > 0 && metadata.label == "Apple 内部原名") {
                LogManager.d(
                    "OnlineTargeter",
                    "使用 Apple 内部原名重试: ${metadata.title} / ${metadata.artist}"
                )
            }
            val outcome = searchSources(
                context = context,
                sources = sources,
                title = metadata.title,
                artist = metadata.artist,
                durationMs = durationMs,
                requireTranslation = requireTranslation,
                metadataLabel = metadata.label,
                album = album,
                originalAlbum = originalAlbum,
                collectSourceStatuses = collectSourceStatuses,
                statusSources = if (index == 0) statusOnlySources else emptyList(),
            )
            if (collectSourceStatuses) {
                outcome.sourceStatuses.forEach { status -> allSourceStatuses.mergeStatus(status) }
            }
            if (outcome.lines != null) {
                if (!collectSourceStatuses) {
                    return SearchOutcome(
                        lines = outcome.lines,
                        wordLines = outcome.wordLines,
                        selectedSource = outcome.selectedSource,
                        sourceStatuses = allSourceStatuses.values.toList(),
                    )
                }
                return SearchOutcome(
                    lines = outcome.lines,
                    wordLines = outcome.wordLines,
                    sourceStatuses = allSourceStatuses.values.toList(),
                    selectedSource = outcome.selectedSource,
                )
            }
            if (outcome.nearMiss != null) {
                bestNearMiss = if (bestNearMiss == null) {
                    outcome.nearMiss
                } else {
                    preferNearMiss(bestNearMiss, outcome.nearMiss)
                }
            }
        }
        return SearchOutcome(
            nearMiss = bestNearMiss,
            sourceStatuses = allSourceStatuses.values.toList(),
            selectedSource = bestNearMiss?.source?.sourceType,
        )
    }

    private suspend fun searchSources(
        context: Context,
        sources: List<SearchSource>,
        title: String,
        artist: String,
        durationMs: Long,
        requireTranslation: Boolean,
        metadataLabel: String,
        album: String?,
        originalAlbum: String?,
        collectSourceStatuses: Boolean,
        statusSources: List<SearchSource> = emptyList(),
    ): SearchOutcome {

        // 统一匹配格式：取歌词/翻译都优先「歌名 歌手 专辑」，
        // 专辑匹配不到时再降级为「歌名 歌手」重试。
        val titleArtistKeyword = "$title $artist"
        val albumForKeyword = album?.trim()?.takeIf(String::isNotEmpty)
            ?: originalAlbum?.trim()?.takeIf(String::isNotEmpty)
        val keyword = if (albumForKeyword == null) {
            titleArtistKeyword
        } else {
            "$title $artist $albumForKeyword"
        }
        val fallbackKeyword = titleArtistKeyword
        LogManager.d(
            "OnlineTargeter",
            "正在搜索: 类型=$metadataLabel, 关键词=\"$keyword\", " +
                "源顺序=${sources.joinToString { it.javaClass.simpleName }}"
        )

        val cleanLocalTitle = cleanString(context, title)
        val localArtists = splitArtists(artist).map { cleanString(context, it) }
        val multiCredit = isMultiCreditArtist(localArtists)
        val cleanLocalAlbum = normalizeAlbum(context, album.orEmpty())
        LogManager.d(
            "OnlineTargeter",
            "专辑比对: 类型=$metadataLabel, 原始专辑=\"$album\", " +
                "归一化=\"$cleanLocalAlbum\""
        )
        val featureKeywords = listOf("live", "remastered", "翻唱", "cover")
        val localFeatures = featureKeywords.filter { title.lowercase().contains(it) }

        val parallelEvaluations = if (collectSourceStatuses) {
            val candidateTypes = sources.mapTo(linkedSetOf()) { it.sourceType }
            val statusOnlyWork = statusSources
                .filterNot { it.sourceType in candidateTypes }
            val work = buildList {
                sources.forEach { add(it to true) }
                statusOnlyWork.forEach { add(it to false) }
            }
            coroutineScope {
                val evaluations = work.associate { (source, allowFallbackRetry) ->
                    source.sourceType to async {
                        source.sourceType to evaluateSource(
                            context = context,
                            source = source,
                            title = title,
                            keyword = keyword,
                            fallbackKeyword = fallbackKeyword,
                            artist = artist,
                            durationMs = durationMs,
                            requireTranslation = requireTranslation,
                            metadataLabel = metadataLabel,
                            cleanLocalTitle = cleanLocalTitle,
                            localArtists = localArtists,
                            localFeatures = localFeatures,
                            cleanLocalAlbum = cleanLocalAlbum,
                            multiCredit = multiCredit,
                            albumForKeyword = albumForKeyword,
                            allowFallbackRetry = allowFallbackRetry,
                        )
                    }
                }
                if (
                    shouldWaitForStatusOnlySources(
                        candidateSourceCount = sources.size,
                        statusOnlySourceCount = statusOnlyWork.size,
                    )
                ) {
                    evaluations.values.awaitAll().toMap()
                } else {
                    // 严格歌词源切换只需要等待目标源。其他来源的状态已有历史值，
                    // 不能让无关网络请求把目标行长期卡在「获取中」。
                    val candidateResults = sources.associate { source ->
                        evaluations.getValue(source.sourceType).await()
                    }
                    statusOnlyWork.forEach { source ->
                        evaluations[source.sourceType]?.cancel()
                    }
                    candidateResults
                }
            }
        } else {
            emptyMap()
        }

        var bestScore = -1
        var bestNearMiss: NearMiss? = null
        var selectedSuccess: SourceAttempt? = null
        val sourceStatuses = linkedMapOf<Source, AppleMissingLyricsSourceStatus>()

        for (source in sources) {
            val evaluation = if (collectSourceStatuses) {
                parallelEvaluations.getValue(source.sourceType)
            } else {
                evaluateSource(
                    context = context,
                    source = source,
                    title = title,
                    keyword = keyword,
                    fallbackKeyword = fallbackKeyword,
                    artist = artist,
                    durationMs = durationMs,
                    requireTranslation = requireTranslation,
                    metadataLabel = metadataLabel,
                    cleanLocalTitle = cleanLocalTitle,
                    localArtists = localArtists,
                    localFeatures = localFeatures,
                    cleanLocalAlbum = cleanLocalAlbum,
                    multiCredit = multiCredit,
                    albumForKeyword = albumForKeyword,
                    allowFallbackRetry = true,
                )
            }
            var attempt = evaluation.attempt
            if (collectSourceStatuses) {
                evaluation.statuses.forEach { status -> sourceStatuses.mergeStatus(status) }
            }
            if (attempt.lines != null) {
                if (selectedSuccess == null) selectedSuccess = attempt
                if (!collectSourceStatuses) {
                    return SearchOutcome(
                        lines = attempt.lines,
                        wordLines = attempt.wordLines,
                        selectedSource = source.sourceType,
                    )
                }
            }
            if (attempt.song != null) {
                if (attempt.score > bestScore) bestScore = attempt.score
                if (!attempt.passAttempted) {
                    nearMissFor(source, attempt, multiCredit)?.let { nearMiss ->
                        bestNearMiss = if (bestNearMiss == null) {
                            nearMiss
                        } else {
                            preferNearMiss(bestNearMiss, nearMiss)
                        }
                    }
                }
            }
        }
        if (collectSourceStatuses) {
            // 补充歌词弹窗要求每个来源都给出「已检索到」或「检索失败」，
            // 不能因为某来源不在候选顺序里就显示「未检索」。这里只补状态，
            // 不参与歌词/近失候选选择。
            statusSources.forEach { statusSource ->
                parallelEvaluations[statusSource.sourceType]
                    ?.statuses
                    ?.forEach { status -> sourceStatuses.mergeStatus(status) }
            }
        }
        if (selectedSuccess == null) {
            LogManager.d(
                "OnlineTargeter",
                "歌词未命中: 类型=$metadataLabel, 最佳得分=$bestScore, 阈值=$PASS_SCORE"
            )
        }
        return SearchOutcome(
            lines = selectedSuccess?.lines,
            wordLines = selectedSuccess?.wordLines,
            nearMiss = bestNearMiss,
            sourceStatuses = sourceStatuses.values.toList(),
            selectedSource = selectedSuccess?.song?.source ?: bestNearMiss?.source?.sourceType,
        )
    }

    private data class SourceEvaluation(
        val attempt: SourceAttempt,
        val statuses: List<AppleMissingLyricsSourceStatus>,
    )

    private suspend fun evaluateSource(
        context: Context,
        source: SearchSource,
        title: String,
        keyword: String,
        fallbackKeyword: String,
        artist: String,
        durationMs: Long,
        requireTranslation: Boolean,
        metadataLabel: String,
        cleanLocalTitle: String,
        localArtists: List<String>,
        localFeatures: List<String>,
        cleanLocalAlbum: String,
        multiCredit: Boolean,
        albumForKeyword: String?,
        allowFallbackRetry: Boolean,
    ): SourceEvaluation {
        val sourceKeyword = if (source.sourceType == Source.KUGOU && artist.isNotBlank()) {
            buildString {
                append(artist).append(" - ").append(title)
                albumForKeyword?.let { append(' ').append(it) }
            }
        } else {
            keyword
        }
        val statuses = mutableListOf<AppleMissingLyricsSourceStatus>()
        var attempt = scoreSource(
            context = context,
            source = source,
            keyword = sourceKeyword,
            durationMs = durationMs,
            requireTranslation = requireTranslation,
            metadataLabel = metadataLabel,
            cleanLocalTitle = cleanLocalTitle,
            localArtists = localArtists,
            localFeatures = localFeatures,
            cleanLocalAlbum = cleanLocalAlbum,
        )
        statuses += attempt.status
        // 优先「歌名 歌手 专辑」；专辑（或多人署名）没匹配到歌词时，降级为「歌名 歌手」重试。
        val albumKeywordIncluded = keyword != fallbackKeyword
        val shouldRetry = allowFallbackRetry && fallbackKeyword.isNotBlank() &&
            attempt.lines == null &&
            (
                albumKeywordIncluded ||
                    (multiCredit && (attempt.song == null || !attempt.artistMatched))
                )
        if (shouldRetry) {
            LogManager.d(
                "OnlineTargeter",
                "专辑/署名未匹配，降级为「歌名 歌手」重试: " +
                    "源=${source.javaClass.simpleName}, 关键词=\"$fallbackKeyword\"",
            )
            val retry = scoreSource(
                context = context,
                source = source,
                keyword = fallbackKeyword,
                durationMs = durationMs,
                requireTranslation = requireTranslation,
                metadataLabel = metadataLabel,
                cleanLocalTitle = cleanLocalTitle,
                localArtists = localArtists,
                localFeatures = localFeatures,
                cleanLocalAlbum = cleanLocalAlbum,
            )
            statuses += retry.status
            attempt = betterSourceAttempt(attempt, retry)
        }
        return SourceEvaluation(attempt = attempt, statuses = statuses)
    }

    private suspend fun scoreSource(
        context: Context,
        source: SearchSource,
        keyword: String,
        durationMs: Long,
        requireTranslation: Boolean,
        metadataLabel: String,
        cleanLocalTitle: String,
        localArtists: List<String>,
        localFeatures: List<String>,
        cleanLocalAlbum: String,
    ): SourceAttempt {
        val results = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                source.search(keyword, 1, "/", 20, durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w(
                    "OnlineTargeter",
                    "搜索异常: 源=${source.javaClass.simpleName}, ${e.message}",
                )
                null
            }
        }
        if (results.isNullOrEmpty()) {
            LogManager.d(
                "OnlineTargeter",
                "搜索结果为空: 源=${source.javaClass.simpleName}, 关键词=\"$keyword\"",
            )
            return SourceAttempt(
                status = AppleMissingLyricsSourceStatus(
                    source = source.sourceType.name,
                    searched = true,
                )
            )
        }
        LogManager.d(
            "OnlineTargeter",
            "搜索结果: 源=${source.javaClass.simpleName}, 关键词=\"$keyword\", " +
                "数量=${results.size}",
        )

        var localBestScore = -1
        var bestSong: SongSearchResult? = null
        var titleMatched = false
        var durationClose = false
        var artistMatched = false

        for (song in results) {
            val score = calculateScore(
                context,
                song,
                cleanLocalTitle,
                localArtists,
                localFeatures,
                durationMs,
                cleanLocalAlbum,
            )
            val songTitleMatches = isStrongTitleMatch(
                cleanLocalTitle,
                cleanString(context, song.title),
            )
            val songDurationClose = isStrongDurationMatch(durationMs, song.duration)
            val songArtistMatches = hasCommonArtist(
                localArtists,
                splitArtists(song.artist).map { cleanString(context, it) },
            )
            if (score > localBestScore) {
                localBestScore = score
                bestSong = song
                titleMatched = songTitleMatches
                durationClose = songDurationClose
                artistMatched = songArtistMatches
            }
        }

        LogManager.d(
            "OnlineTargeter",
            "评分: \"${bestSong?.title}\" - \"${bestSong?.artist}\", 关键词=\"$keyword\", " +
                "得分=$localBestScore, 阈值=$PASS_SCORE, 通过=${localBestScore >= PASS_SCORE}",
        )

        if (localBestScore >= PASS_SCORE && bestSong != null) {
            val lyricsResult = withTimeoutOrNull(TIMEOUT_MS) {
                try {
                    source.getLyrics(bestSong)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogManager.w(
                        "OnlineTargeter",
                        "获取歌词异常: 源=${source.javaClass.simpleName}, ${e.message}",
                    )
                    null
                }
            }
            if (lyricsResult != null &&
                (lyricsResult.original.isNotEmpty() || !lyricsResult.translated.isNullOrEmpty())
            ) {
                val list = toLrcLines(lyricsResult)
                if (list.isNotEmpty()) {
                    if (requireTranslation && list.none {
                            OnlineTranslationContentPolicy.isMeaningful(it.translation)
                        }
                    ) {
                        LogManager.d(
                            "OnlineTargeter",
                            "当前源无可用翻译，继续尝试后续源: " +
                                "源=${source.javaClass.simpleName}",
                        )
                        return SourceAttempt(
                            song = bestSong,
                            score = localBestScore,
                            titleMatched = titleMatched,
                            durationClose = durationClose,
                            artistMatched = artistMatched,
                            passAttempted = true,
                            status = statusFor(source.sourceType, lyricsResult, list),
                        )
                    }
                    LogManager.d(
                        "OnlineTargeter",
                        "歌词命中: 类型=$metadataLabel, 源=${source.javaClass.simpleName}, " +
                            "关键词=\"$keyword\", 得分=$localBestScore, 行数=${list.size}",
                    )
                    return SourceAttempt(
                        lines = list,
                        wordLines = toWordLines(lyricsResult),
                        song = bestSong,
                        score = localBestScore,
                        status = statusFor(source.sourceType, lyricsResult, list),
                    )
                }
            }
            return SourceAttempt(
                song = bestSong,
                score = localBestScore,
                titleMatched = titleMatched,
                durationClose = durationClose,
                artistMatched = artistMatched,
                passAttempted = true,
                status = AppleMissingLyricsSourceStatus(
                    source = source.sourceType.name,
                    searched = true,
                ),
            )
        }
        return SourceAttempt(
            song = bestSong,
            score = localBestScore,
            titleMatched = titleMatched,
            durationClose = durationClose,
            artistMatched = artistMatched,
            status = AppleMissingLyricsSourceStatus(
                source = source.sourceType.name,
                searched = true,
            ),
        )
    }

    private fun nearMissFor(
        source: SearchSource,
        attempt: SourceAttempt,
        multiCredit: Boolean,
    ): NearMiss? {
        val song = attempt.song ?: return null
        return when {
            isNearMissEligible(attempt.score, attempt.titleMatched, attempt.durationClose) ->
                NearMiss(source, song, attempt.score, durationVerified = true)
            isLyricFallbackEligible(
                titleMatched = attempt.titleMatched,
                multiCredit = multiCredit,
                durationClose = attempt.durationClose,
            ) -> NearMiss(source, song, attempt.score, durationVerified = false)
            else -> null
        }
    }

    private fun betterSourceAttempt(first: SourceAttempt, retry: SourceAttempt): SourceAttempt =
        when {
            retry.song == null -> first
            first.song == null -> retry
            retry.durationClose && !first.durationClose -> retry
            first.durationClose && !retry.durationClose -> first
            retry.score > first.score -> retry
            else -> first
        }

    private fun preferNearMiss(current: NearMiss, candidate: NearMiss): NearMiss = when {
        current.durationVerified && !candidate.durationVerified -> current
        !current.durationVerified && candidate.durationVerified -> candidate
        candidate.score > current.score -> candidate
        else -> current
    }

    private data class SourceAttempt(
        val lines: List<LrcLine>? = null,
        val wordLines: List<LyricsLine>? = null,
        val song: SongSearchResult? = null,
        val score: Int = -1,
        val titleMatched: Boolean = false,
        val durationClose: Boolean = false,
        val artistMatched: Boolean = false,
        val passAttempted: Boolean = false,
        val status: AppleMissingLyricsSourceStatus = AppleMissingLyricsSourceStatus(
            source = "",
            searched = false,
        ),
    )

    private data class NearMiss(
        val source: SearchSource,
        val song: SongSearchResult,
        val score: Int,
        val durationVerified: Boolean,
    )

    private data class SearchOutcome(
        val lines: List<LrcLine>? = null,
        val wordLines: List<LyricsLine>? = null,
        val nearMiss: NearMiss? = null,
        val sourceStatuses: List<AppleMissingLyricsSourceStatus> = emptyList(),
        val selectedSource: Source? = null,
    )

    private fun statusFor(
        source: Source,
        result: LyricsResult,
        lines: List<LrcLine>,
    ): AppleMissingLyricsSourceStatus {
        val wordTimed = result.original.any { line ->
            line.words.size > 1 && line.words.any { word -> word.end > word.start }
        }
        return AppleMissingLyricsSourceStatus(
            source = source.name,
            searched = true,
            found = lines.isNotEmpty(),
            wordTimed = wordTimed,
            lineCount = lines.size,
        )
    }

    private fun MutableMap<Source, AppleMissingLyricsSourceStatus>.mergeStatus(
        status: AppleMissingLyricsSourceStatus,
    ) {
        if (status.source.isBlank()) return
        val source = runCatching { Source.valueOf(status.source) }.getOrNull() ?: return
        val previous = this[source]
        this[source] = when {
            previous == null -> status
            status.found && !previous.found -> status
            status.found && previous.found && status.lineCount > previous.lineCount -> status
            else -> previous.copy(searched = previous.searched || status.searched)
        }
    }

    private fun mergeStatuses(
        statuses: List<AppleMissingLyricsSourceStatus>,
        status: AppleMissingLyricsSourceStatus,
    ): List<AppleMissingLyricsSourceStatus> {
        val merged = linkedMapOf<Source, AppleMissingLyricsSourceStatus>()
        statuses.forEach { existing -> merged.mergeStatus(existing) }
        merged.mergeStatus(status)
        return merged.values.toList()
    }

    internal fun isStrongTitleMatch(localTitle: String, remoteTitle: String): Boolean =
        localTitle.isNotEmpty() && (
            localTitle == remoteTitle ||
                remoteTitle.contains(localTitle) ||
                localTitle.contains(remoteTitle)
            )

    internal fun isStrongDurationMatch(localDurationMs: Long, remoteDurationMs: Long): Boolean =
        localDurationMs <= 0L ||
            abs(localDurationMs - remoteDurationMs) < STRONG_DURATION_TOLERANCE_MS

    internal fun isNearMissEligible(
        score: Int,
        titleMatched: Boolean,
        durationClose: Boolean,
    ): Boolean = score >= NEAR_MISS_MIN_SCORE && titleMatched && durationClose

    internal fun isMultiCreditArtist(artists: List<String>): Boolean =
        artists.count(String::isNotBlank) >= 2

    internal fun hasCommonArtist(
        localArtists: List<String>,
        remoteArtists: List<String>,
    ): Boolean = localArtists.any { local ->
        local.isNotBlank() && remoteArtists.any { remote ->
            remote.isNotBlank() &&
                (local == remote || remote.contains(local) || local.contains(remote))
        }
    }

    internal fun isLyricFallbackEligible(
        titleMatched: Boolean,
        multiCredit: Boolean,
        durationClose: Boolean,
    ): Boolean = titleMatched && multiCredit && !durationClose

    internal fun shouldRetryWithOriginalMetadata(
        title: String,
        artist: String,
        originalTitle: String?,
        originalArtist: String?
    ): Boolean {
        val resolvedTitle = originalTitle?.trim().orEmpty()
        val resolvedArtist = originalArtist?.trim().orEmpty()
        if (resolvedTitle.isEmpty() && resolvedArtist.isEmpty()) return false
        return !resolvedTitle.equals(title.trim(), ignoreCase = true) ||
            !resolvedArtist.equals(artist.trim(), ignoreCase = true)
    }

    internal fun resolveMetadataSearchOrder(
        preferOriginalMetadata: Boolean,
        hasDistinctOriginalMetadata: Boolean,
    ): List<Boolean> = when {
        !hasDistinctOriginalMetadata -> listOf(false)
        preferOriginalMetadata -> listOf(true, false)
        else -> listOf(false, true)
    }

    internal fun resolveSourceOrder(
        pkgName: String,
        preferredSource: Source?,
        fallbackToOtherSources: Boolean = true
    ): List<Source> {
        if (!fallbackToOtherSources && preferredSource != null) {
            return listOf(preferredSource)
        }
        return when (preferredSource) {
            Source.NE -> listOf(Source.NE, Source.QM)
            Source.QM -> listOf(Source.QM, Source.NE)
            Source.KUWO -> listOf(Source.KUWO, Source.NE, Source.QM)
            Source.KUGOU -> listOf(Source.KUGOU, Source.NE, Source.QM)
            Source.LRCLIB -> listOf(Source.LRCLIB, Source.NE, Source.QM)
            Source.LB -> listOf(Source.LB)
            null -> when (pkgName) {
                "com.netease.cloudmusic" -> listOf(Source.NE, Source.QM)
                "com.tencent.qqmusic", "com.tencent.qqmusicpad" ->
                    listOf(Source.QM, Source.NE)
                else -> listOf(Source.QM, Source.NE)
            }
        }
    }

    internal fun shouldWaitForStatusOnlySources(
        candidateSourceCount: Int,
        statusOnlySourceCount: Int,
    ): Boolean = candidateSourceCount != 1 || statusOnlySourceCount == 0

    /** 保留来源逐词时间轴的行列表，供需要逐字渲染的消费方使用。 */
    internal fun toWordLines(lyricsResult: LyricsResult): List<LyricsLine> =
        lyricsResult.original.filter { line ->
            line.words.joinToString("") { it.text }.trim().isNotEmpty()
        }

    internal fun toLrcLines(lyricsResult: LyricsResult): List<LrcLine> {
        val translationsByStart = lyricsResult.translated.orEmpty().associate { line ->
            line.start to line.words.joinToString("") { it.text }.trim()
        }
        val romanizationsByStart = lyricsResult.romanization.orEmpty().associate { line ->
            line.start to line.words
                .map { it.text.trim() }
                .filter(String::isNotEmpty)
                .joinToString(" ")
        }
        return lyricsResult.original.mapNotNull { line ->
            val content = line.words.joinToString("") { it.text }.trim()
            if (content.isEmpty()) return@mapNotNull null
            LrcLine(
                startTimeMs = line.start,
                content = content,
                translation = OnlineTranslationContentPolicy.sanitize(
                    translationsByStart[line.start]
                ),
                romanization = RomanizationPolicy.sanitize(
                    originalText = content,
                    pronunciation = romanizationsByStart[line.start],
                ),
            )
        }
    }

    private fun calculateScore(
        context: Context,
        song: SongSearchResult,
        cleanLocalTitle: String,
        localArtists: List<String>,
        localFeatures: List<String>,
        localDurationMs: Long,
        cleanLocalAlbum: String,
    ): Int {
        var score = 0

        if (localDurationMs > 0 && song.duration > 0) {
            score += durationScore(localDurationMs, song.duration)
        } else {
            // 时长缺失（如 MV 视频版本时长不可信被跳过）时给中性分而非 0：
            // 时长未知 ≠ 时长不匹配，标题+歌手双命中已足够身份确认，
            // 否则 MV 歌即使全对也只有 80 分、永远差 5 分达不到阈值。
            score += 10
        }

        val cleanSongTitle = cleanString(context, song.title)

        // 标题比对额外接受紧凑形式（忽略全部空格）：K-pop/J-pop 歌名常见
        // 驼式写法（SleeplessNight）与曲库分词写法（Sleepless Night）的等价场景。
        val compactLocalTitle = compactWhitespace(cleanLocalTitle)
        val compactSongTitle = compactWhitespace(cleanSongTitle)
        if (
            cleanLocalTitle == cleanSongTitle ||
            cleanSongTitle.contains(cleanLocalTitle) ||
            cleanLocalTitle.contains(cleanSongTitle) ||
            (compactLocalTitle.isNotEmpty() && (
                compactSongTitle == compactLocalTitle ||
                    compactSongTitle.contains(compactLocalTitle) ||
                    compactLocalTitle.contains(compactSongTitle)
                ))
        ) {
            score += 50
        }

        val songArtists = splitArtists(song.artist).map { cleanString(context, it) }
        
        val hasCommonArtist = localArtists.any { lArtist -> songArtists.any { sArtist -> lArtist == sArtist || sArtist.contains(lArtist) || lArtist.contains(sArtist) } }
        if (hasCommonArtist) {
            score += 30
        }

        val remoteAlbum = normalizeAlbum(context, song.album)
        val delta = albumScore(cleanLocalAlbum, remoteAlbum)
        score += delta
        LogManager.d(
            "OnlineTargeter",
            "专辑比对: 本地=\"$cleanLocalAlbum\", 候选=\"$remoteAlbum\", " +
                "源=${song.source}, 得分=$delta"
        )

        val songFeatures = listOf("live", "remastered", "翻唱", "cover").filter { song.title.lowercase().contains(it) }
        
        if (localFeatures.isNotEmpty() && songFeatures.isNotEmpty()) {
            val commonFeatures = localFeatures.intersect(songFeatures.toSet())
            if (commonFeatures.isNotEmpty()) {
                score += 20
            }
        }

        return score
    }

    internal fun albumScore(cleanLocalAlbum: String, cleanRemoteAlbum: String): Int {
        val localBase = stripAlbumVersionSuffixes(cleanLocalAlbum)
        val remoteBase = stripAlbumVersionSuffixes(cleanRemoteAlbum)
        return when {
            cleanLocalAlbum.isEmpty() || cleanRemoteAlbum.isEmpty() -> 0
            cleanLocalAlbum == cleanRemoteAlbum -> 10
            localBase.isNotEmpty() && localBase == remoteBase -> 5
            else -> 0
        }
    }

    /**
     * 专辑名参与评分前的字符归一化。NFKC 会把全角字母/数字/括号统一成半角，
     * 随后 [cleanString] 继续去掉括号内容、压缩空白、转简体并小写。
     */
    internal fun normalizeAlbumCharacters(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFKC)

    private fun normalizeAlbum(context: Context, input: String): String =
        cleanString(context, normalizeAlbumCharacters(input))

    /**
     * 去掉末尾的版本/录音标记，让“原曲 / 现场 / 不插电 / 翻唱 / 豪华版”等
     * 同一首歌词的变体在专辑维度不被误判成不同专辑。只从尾部逐段剥离，避免
     * 误伤普通专辑名（例如 Greatest Hits 不会被拆成 Greatest）。
     */
    internal fun stripAlbumVersionSuffixes(value: String): String {
        var result = value.trim()
        var changed: Boolean
        do {
            changed = false
            for (suffix in ALBUM_VERSION_SUFFIXES.sortedByDescending { it.length }) {
                val stripped = stripTrailingVersionSuffix(result, suffix)
                if (stripped != result) {
                    result = stripped
                    changed = true
                    break
                }
            }
        } while (changed)
        return result
    }

    private fun stripTrailingVersionSuffix(value: String, suffix: String): String {
        if (suffix.isEmpty() || !value.endsWith(suffix, ignoreCase = true)) return value
        if (value.length == suffix.length) return ""

        val boundary = value.length - suffix.length
        val preceding = value[boundary - 1]
        // 中文后缀（如“豪华版”）是独立词，可直接附着在英文专辑名后。
        // 英文后缀要求前方是分隔符，避免把 "Alive" 误剥成 "live"。
        val suffixIsCjk = suffix.any { it.isCjkUnifiedIdeograph() }
        if (!suffixIsCjk && preceding.isLetterOrDigit()) return value

        return value.substring(0, boundary).trimEnd { it in ALBUM_SUFFIX_SEPARATOR_CHARS }
    }

    private fun Char.isCjkUnifiedIdeograph(): Boolean =
        this.code in 0x4E00..0x9FFF

    private val ALBUM_SUFFIX_SEPARATOR_CHARS = setOf(' ', '-', '_', '~', '·', '|', '/')

    private val ALBUM_VERSION_SUFFIXES = listOf(
        // 英文版本 / 录音标记，长后缀在前。
        "live version", "acoustic version", "piano version", "studio version",
        "radio version", "deluxe edition", "full version", "clean version",
        "radio edit", "tv size", "hi-res", "320k",
        "live", "acoustic", "unplugged", "cover", "remastered", "remaster",
        "remix", "remixes", "deluxe", "explicit", "clean", "edited",
        "instrumental", "piano", "demo", "full", "studio", "radio", "edit",
        "single", "ep", "flac", "lossless",
        // 中文版本 / 录音标记。
        "现场版", "演唱会版", "不插电版", "木吉他版", "翻唱版", "重制版",
        "重置版", "混音版", "豪华版", "伴奏版", "纯音乐版", "钢琴版",
        "试听版", "完整版", "录音室版", "电台版", "单曲版", "无损版",
        "高音质版", "cover版", "remix版", "tv版", "短版", "剪辑版",
        "现场", "演唱会", "不插电", "吉他版", "翻唱", "重制", "重置",
        "混音", "豪华", "伴奏", "纯音乐", "试听", "录音室", "电台",
        "单曲", "无损", "高音质", "版",
    )

    private fun cleanString(context: Context, input: String): String {
        val cleaned = input.replace(Regex("\\(.*?\\)|\\[.*?]|\\{.*?\\}"), "").trim().lowercase()
        return compactWhitespace(ChineseUtils.toSimplified(context, cleaned))
    }

    internal fun durationScore(localDurationMs: Long, remoteDurationMs: Long): Int {
        val diffMs = abs(localDurationMs - remoteDurationMs)
        return when {
            diffMs > 5_000L -> -30
            diffMs < 1_500L -> 15
            else -> 10
        }
    }

    internal fun compactWhitespace(value: String): String = value.replace(Regex("\\s+"), "")

    // ---- 脏标题净化：仅影响搜索关键词与评分，不改变岛上展示的原始标题 ----
    private val adBadgeRegex =
        Regex("(?:\\(\\s*)?(?:TME\\s*)?黑胶\\s*VIP(?:\\s*\\))?", RegexOption.IGNORE_CASE)
    private val mvQuotedTitleRegex = Regex(
        "^.*?['‘“]([^'’”]{1,120})['’”]\\s*(?:(?:official\\s+)?(?:mv|music\\s*video|lyric\\s*video|m/v))?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val mvSuffixRegex = Regex(
        "\\s*[(（\\[【]?\\s*(?:(?:official\\s+)?(?:mv|music\\s*video|lyric\\s*video|visualizer|m/v))\\s*[])）】】]?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val segmentSeparatorRegex = Regex("\\s*[-–—|·]\\s*")

    /**
     * 清洗播放器侧常见的脏标题后再用于搜索/评分：
     * - 广告角标：`TME黑胶VIP`、`黑胶VIP`（Spotify 站内活动角标）；
     * - MV 类尾缀：`'Song' MV`、`(Official MV)`、`Song MV` 等（YTM 音乐视频标题）；
     * - 分段去重：`INFINITY - INFINITY` 压成 `INFINITY`，并剔除与歌手重复的尾段。
     */
    internal data class SanitizedTitle(
        val value: String,
        /** 是否剥离了 MV 视频类尾缀：此时媒体时长（视频长度）不可信，不应参与计分。 */
        val mvStripped: Boolean,
    )

    internal fun sanitizeLyricSearchTitle(title: String, artist: String?): SanitizedTitle {
        val raw = title.trim()
        if (raw.isEmpty()) return SanitizedTitle(raw, mvStripped = false)
        var value = raw.replace(adBadgeRegex, " ").trim()
        mvQuotedTitleRegex.find(value)?.let { match ->
            return SanitizedTitle(match.groupValues[1].trim(), mvStripped = true)
        }
        val mvBefore = value
        value = value.replace(mvSuffixRegex, " ").trim()
        val mvStripped = value != mvBefore
        val artistCompact = compactWhitespace(artist.orEmpty())
        val segments = value.split(segmentSeparatorRegex)
            .map(::compactWhitespace)
            .filter(String::isNotEmpty)
        if (segments.isEmpty()) return SanitizedTitle(raw, mvStripped)
        val kept = mutableListOf<String>()
        for (segment in segments) {
            if (artistCompact.isNotEmpty() && segment.equals(artistCompact, ignoreCase = true)) {
                continue
            }
            if (kept.lastOrNull()?.equals(segment, ignoreCase = true) == true) continue
            kept += segment
        }
        return SanitizedTitle(kept.joinToString(" ").ifEmpty { raw }, mvStripped)
    }

    /**
     * 针对某一首歌，逐个平台来源跑一次匹配评分，用于设置页展示“匹配率 / 搜索失败”。
     * 与 [fetchBestLyric] 共用 [scoreSource] 的评分逻辑，但只采集每个来源的评分与结果，
     * 不挑选最优来源、不返回歌词，也不影响 AIDL 跨进程的状态模型。
     */
    suspend fun diagnoseSourceMatches(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        sourceOrder: List<Source>,
    ): List<SourceMatchDiagnostic> {
        val ne = LyricApiProvider.getNeSource(context)
        val qm = LyricApiProvider.qmSource
        val sourcesByType = mapOf(
            Source.NE to ne,
            Source.QM to qm,
            Source.KUWO to LyricApiProvider.kuwoSource,
            Source.KUGOU to LyricApiProvider.kugouSource,
            Source.LRCLIB to LyricApiProvider.lrclibSource,
        )
        val sources = sourceOrder.mapNotNull(sourcesByType::get)
        if (sources.isEmpty() || title.isBlank()) return emptyList()
        val sanitized = sanitizeLyricSearchTitle(title, artist)
        val searchTitle = sanitized.value
        val searchArtist = artist.trim()
        // 仅 MV 视频尾缀剥离时时长不可信；分段净化不影响时长可信度。
        val effectiveDurationMs = if (sanitized.mvStripped) 0L else durationMs
        val cleanLocalTitle = cleanString(context, searchTitle)
        val localArtists = splitArtists(searchArtist).map { cleanString(context, it) }
        val multiCredit = isMultiCreditArtist(localArtists)
        val cleanLocalAlbum = normalizeAlbum(context, album)
        val featureKeywords = listOf("live", "remastered", "翻唱", "cover")
        val localFeatures = featureKeywords.filter { searchTitle.lowercase().contains(it) }
        return sources.map { source ->
            try {
                val attempt = scoreSource(
                    context = context,
                    source = source,
                    keyword = if (album.isBlank()) {
                        "$searchTitle $searchArtist"
                    } else {
                        "$searchTitle $searchArtist ${album.trim()}"
                    },
                    durationMs = effectiveDurationMs,
                    requireTranslation = false,
                    metadataLabel = "诊断",
                    cleanLocalTitle = cleanLocalTitle,
                    localArtists = localArtists,
                    localFeatures = localFeatures,
                    cleanLocalAlbum = cleanLocalAlbum,
                )
                // 与 fetchBestLyric 一致：仅“评分未达阈值”的候选才可能走近失兜底；
                // 达标但取词失败属于取词异常，不算近失候选。
                val nearMissEligible = attempt.song != null && attempt.lines == null &&
                    attempt.score < PASS_SCORE && (
                    isNearMissEligible(attempt.score, attempt.titleMatched, attempt.durationClose) ||
                        isLyricFallbackEligible(
                            titleMatched = attempt.titleMatched,
                            multiCredit = multiCredit,
                            durationClose = attempt.durationClose,
                        )
                    )
                SourceMatchDiagnostic(
                    source = source.sourceType,
                    searched = true,
                    score = attempt.score,
                    found = attempt.lines != null,
                    lineCount = attempt.lines?.size ?: 0,
                    nearMissEligible = nearMissEligible,
                    durationMs = attempt.song?.duration ?: 0L,
                )
            } catch (e: Exception) {
                SourceMatchDiagnostic(
                    source = source.sourceType,
                    searched = false,
                    score = -1,
                    found = false,
                    lineCount = 0,
                    errorMessage = e.message,
                )
            }
        }
    }

    /**
     * 「二次匹配」：按用户输入的「歌名 + 歌手（必填）+ 专辑（可选）」在已启用来源里搜索候选。
     * 返回的结果会按与输入条件的相关性排序，并剔除完全无关的候选。
     */
    suspend fun searchCandidates(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        sourceOrder: List<Source>,
        pageSize: Int = 20,
    ): List<SongSearchResult> {
        val normalizedTitle = title.trim()
        val normalizedArtist = artist.trim()
        if (normalizedTitle.isEmpty() || normalizedArtist.isEmpty()) return emptyList()
        val keyword = buildString {
            append(normalizedTitle)
            append(' ')
            append(normalizedArtist)
            album?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(' ')
                append(it)
            }
        }
        val sourcesByType = buildSearchSources(context)
        val sources = sourceOrder.distinct().mapNotNull(sourcesByType::get)
        if (sources.isEmpty()) return emptyList()
        LogManager.d(
            "OnlineTargeter",
            "二次匹配搜索: 关键词=\"$keyword\", 源=${sources.joinToString { it.sourceType.name }}",
        )
        val rawResults = coroutineScope {
            sources.map { source ->
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        runCatching {
                            source.search(
                                keyword = keyword,
                                page = 1,
                                pageSize = pageSize,
                                durationMs = 0L,
                            )
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll().flatten()
        }
        return rankManualMatchCandidates(
            context = context,
            queryTitle = normalizedTitle,
            queryArtist = normalizedArtist,
            queryAlbum = album?.trim(),
            candidates = rawResults,
        )
    }

    /**
     * 对二次匹配搜索结果进行本地相关性评分与排序。
     *
     * 评分维度：标题（最高 55）、歌手（最高 35）、专辑（最高 15）。
     * 最终按分数降序排列，并剔除既未命中标题也未命中歌手的完全无关项。
     */
    private fun rankManualMatchCandidates(
        context: Context,
        queryTitle: String,
        queryArtist: String,
        queryAlbum: String?,
        candidates: List<SongSearchResult>,
    ): List<SongSearchResult> {
        val cleanTitle = cleanString(context, queryTitle)
        val compactTitle = compactWhitespace(cleanTitle)
        val cleanArtists = splitArtists(queryArtist).map { cleanString(context, it) }
        val cleanAlbum = queryAlbum?.let { normalizeAlbum(context, it) }.orEmpty()

        data class Scored(
            val result: SongSearchResult,
            val score: Int,
        )

        val scored = candidates.map { candidate ->
            val candidateTitle = cleanString(context, candidate.title)
            val candidateCompactTitle = compactWhitespace(candidateTitle)
            val candidateArtists = splitArtists(candidate.artist).map { cleanString(context, it) }
            val candidateAlbum = normalizeAlbum(context, candidate.album)
            val candidateCompactAlbum = compactWhitespace(candidateAlbum)

            var score = 0

            // 标题匹配：支持完全相等、包含关系、紧凑去空白
            when {
                candidateTitle == cleanTitle -> score += 50
                candidateTitle.contains(cleanTitle) || cleanTitle.contains(candidateTitle) -> score += 40
                compactTitle.isNotEmpty() && (
                    candidateCompactTitle == compactTitle ||
                        candidateCompactTitle.contains(compactTitle) ||
                        compactTitle.contains(candidateCompactTitle)
                    ) -> score += 35
            }
            // 原始大小写不敏感的精确相等给予小幅加分，用于区分同名不同曲
            if (candidate.title.trim().equals(queryTitle.trim(), ignoreCase = true)) {
                score += 5
            }

            // 歌手匹配
            if (hasCommonArtist(cleanArtists, candidateArtists)) {
                score += 30
            }
            if (candidate.artist.trim().equals(queryArtist.trim(), ignoreCase = true)) {
                score += 5
            }

            // 专辑匹配
            if (cleanAlbum.isNotEmpty() && candidateAlbum.isNotEmpty()) {
                when {
                    cleanAlbum == candidateAlbum -> score += 10
                    stripAlbumVersionSuffixes(cleanAlbum) == stripAlbumVersionSuffixes(candidateAlbum) -> score += 5
                }
                if (candidate.album.trim().equals(queryAlbum?.trim().orEmpty(), ignoreCase = true)) {
                    score += 5
                }
            }

            Scored(candidate, score)
        }

        // 只保留至少标题或歌手命中其一的候选，避免展示完全无关歌曲
        val minScore = 20
        val filtered = scored.filter { it.score >= minScore }
        LogManager.d(
            "OnlineTargeter",
            "二次匹配本地筛选: 原始=${candidates.size}, 保留=${filtered.size}, " +
                "最高分=${filtered.maxOfOrNull { it.score } ?: 0}",
        )
        return filtered
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenBy { it.result.source.ordinal }
                    .thenBy { it.result.title.length }
            )
            .map { it.result }
    }

    /**
     * 「二次匹配」：按用户选中的候选，从对应来源抓取歌词（含该来源自带翻译）。
     */
    suspend fun fetchLyricsForCandidate(
        context: Context,
        candidate: SongSearchResult,
    ): List<LrcLine>? {
        val source = buildSearchSources(context)[candidate.source] ?: return null
        val lyricsResult = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { source.getLyrics(candidate) }.getOrNull()
        } ?: return null
        return toLrcLines(lyricsResult).takeIf { it.isNotEmpty() }
    }

    private fun buildSearchSources(context: Context): Map<Source, SearchSource> = mapOf(
        Source.NE to LyricApiProvider.getNeSource(context),
        Source.QM to LyricApiProvider.qmSource,
        Source.KUWO to LyricApiProvider.kuwoSource,
        Source.KUGOU to LyricApiProvider.kugouSource,
        Source.LRCLIB to LyricApiProvider.lrclibSource,
    )

    private fun splitArtists(value: String): List<String> =
        value.split("&", ",", "，", "、", "/", "／")

    private data class SearchMetadata(
        val title: String,
        val artist: String,
        val label: String,
    )

}

/** 设置页“平台来源”针对某一首歌的诊断结果。 */
data class SourceMatchDiagnostic(
    val source: Source,
    /** 该来源是否发起了搜索（含结果或异常）。 */
    val searched: Boolean,
    /** 候选最高评分，范围约 -1（未搜到候选/异常）~ 100。 */
    val score: Int,
    /** 评分达标且成功取到歌词。 */
    val found: Boolean,
    val lineCount: Int,
    val errorMessage: String? = null,
    /** 未达标但满足近失条件（标题精确+时长吻合等），实际抓词会被近失兜底通道采用。 */
    val nearMissEligible: Boolean = false,
    /** 最优候选的时长（毫秒），0 表示未知。 */
    val durationMs: Long = 0L,
)
