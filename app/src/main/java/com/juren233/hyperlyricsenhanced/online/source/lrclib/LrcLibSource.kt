/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.lrclib

import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import com.juren233.hyperlyricsenhanced.online.model.SearchSource
import com.juren233.hyperlyricsenhanced.online.model.SongSearchResult
import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * LRCLIB（https://lrclib.net）开源歌词库。
 *
 * - 免认证，搜索 `GET /api/search`（q 为“歌名 歌手”组合）。
 * - 仅提供 `plainLyrics`（纯文本）与 `syncedLyrics`（标准 LRC），无翻译、无罗马音、无逐字。
 * - `instrumental=true` 表示纯音乐，视为未命中而不是返回空歌词。
 * - 命中后缺失的翻译可由其他已启用源（网易云/QQ 等）通过补翻译链路补齐。
 */
class LrcLibSource : SearchSource {
    override val sourceType: Source = Source.LRCLIB

    override suspend fun search(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int,
        durationMs: Long,
    ): List<SongSearchResult> = withContext(Dispatchers.IO) {
        LrcLibNetwork.search(keyword)
    }

    override suspend fun getLyrics(song: SongSearchResult): LyricsResult? =
        withContext(Dispatchers.IO) {
            if (song.extras[LrcLibSource.EXTRA_INSTRUMENTAL] == "true") return@withContext null
            val synced = song.extras[LrcLibSource.EXTRA_SYNCED_LYRICS]?.takeIf(String::isNotBlank)
                ?: LrcLibNetwork.fetchSyncedLyrics(song)
                ?: return@withContext null
            LrcLibLyricsParser.toLyricsResult(synced)
        }

    companion object {
        const val EXTRA_SYNCED_LYRICS = "lrclib_synced_lyrics"
        const val EXTRA_INSTRUMENTAL = "lrclib_instrumental"
    }
}

private data class LrcLibCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSeconds: Long,
    val instrumental: Boolean,
    val syncedLyrics: String,
)

private object LrcLibNetwork {
    private const val SEARCH_URL = "https://lrclib.net/api/search"
    private const val CLIENT_HEADER =
        "HyperLyrics (github.com/juren233/HyperLyrics-Enhanced)"
    private const val TAG = "LrcLib"

    /** 短期搜索缓存：设置页诊断会高频重复请求，避免触发 LRCLIB 限流（retry-after）。 */
    private const val SEARCH_CACHE_TTL_MS = 60_000L
    private val searchCache = HashMap<String, Pair<Long, JSONArray>>()

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun requestSearchArray(keyword: String): JSONArray {
        val now = System.currentTimeMillis()
        synchronized(searchCache) {
            searchCache[keyword]?.let { (at, cached) ->
                if (now - at < SEARCH_CACHE_TTL_MS) return cached
                searchCache.remove(keyword)
            }
        }
        val array = JSONArray(requestText("$SEARCH_URL?q=${encodeQuery(keyword)}"))
        synchronized(searchCache) {
            if (searchCache.size > 32) searchCache.clear()
            searchCache[keyword] = now to array
        }
        if (array.length() == 0) {
            android.util.Log.w(TAG, "搜索返回空: q=$keyword")
        }
        return array
    }

    /** 字段精确搜索：q 通道为空时的二次回退（部分记录只被字段索引命中）。 */
    private fun requestSearchByFields(track: String, artist: String): JSONArray {
        val encodedTrack = encodeQuery(track)
        val encodedArtist = encodeQuery(artist)
        return JSONArray(
            requestText("$SEARCH_URL?track_name=$encodedTrack&artist_name=$encodedArtist"),
        )
    }

    fun search(keyword: String): List<SongSearchResult> {
        var json = requestSearchArray(keyword)
        if (json.length() == 0) {
            // 播放器可能给本地化艺人名（如“都敬秀”），LRCLIB 只认原文；
            // 剥离非 ASCII 字符后用纯歌名重搜一次。
            val asciiOnly = keyword
                .replace(Regex("[^\\x20-\\x7E]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (asciiOnly.length >= 2 && asciiOnly != keyword) {
                json = requestSearchArray(asciiOnly)
            }
        }
        if (json.length() == 0) {
            // K-pop/J-pop 歌名常见驼式写法（SleeplessNight），曲库记录为分词形式
            //（Sleepless Night）：按小写→大写边界补空格后重搜。
            val camelSpaced = keyword.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            if (camelSpaced != keyword && camelSpaced.length >= 2) {
                json = requestSearchArray(camelSpaced)
            }
        }
        if (json.length() == 0) {
            // YTM 视频标题常为“歌名 - 歌手 - 专辑”拼接，净化后残留的专辑/尾缀 token
            // 会干扰全文检索：逐个丢弃尾部 token 降级重搜。
            val tokens = keyword.split(' ').filter(String::isNotBlank)
            var drop = 1
            while (json.length() == 0 && drop < tokens.size) {
                val reduced = tokens.dropLast(drop).joinToString(" ").trim()
                if (reduced.length >= 2) {
                    json = requestSearchArray(reduced)
                }
                drop++
            }
        }
        if (json.length() == 0) {
            // 最后回退：按最后一个空格拆成 歌名+歌手 走字段精确搜索
            //（LRCLIB 的 q 全文索引与字段索引覆盖不完全一致）。
            val lastSpace = keyword.lastIndexOf(' ')
            if (lastSpace in 1 until keyword.length - 1) {
                val track = keyword.take(lastSpace).trim()
                val artist = keyword.substring(lastSpace + 1).trim()
                if (track.isNotEmpty() && artist.isNotEmpty()) {
                    json = requestSearchByFields(track, artist)
                }
            }
        }
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                add(
                    LrcLibCandidate(
                        id = item.optLong("id"),
                        trackName = item.optString("trackName"),
                        artistName = item.optString("artistName"),
                        albumName = item.optString("albumName"),
                        durationSeconds = item.optLong("duration").coerceAtLeast(0L),
                        instrumental = item.optBoolean("instrumental", false),
                        syncedLyrics = item.optString("syncedLyrics"),
                    )
                )
            }
        }.map { candidate ->
            SongSearchResult(
                id = candidate.id.toString(),
                title = candidate.trackName,
                artist = candidate.artistName,
                album = candidate.albumName,
                duration = candidate.durationSeconds * 1_000L,
                source = Source.LRCLIB,
                extras = buildMap {
                    if (candidate.instrumental) put(LrcLibSource.EXTRA_INSTRUMENTAL, "true")
                    if (candidate.syncedLyrics.isNotBlank()) {
                        put(LrcLibSource.EXTRA_SYNCED_LYRICS, candidate.syncedLyrics)
                    }
                },
            )
        }
    }

    /** getLyrics 兜底：搜索结果未缓存歌词时按曲目信息重新搜索并取同步歌词。 */
    fun fetchSyncedLyrics(song: SongSearchResult): String? {
        val track = encodeQuery(song.title)
        val artist = encodeQuery(song.artist)
        val json = runCatching {
            JSONArray(requestText("$SEARCH_URL?track_name=$track&artist_name=$artist"))
        }.getOrNull() ?: return null
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            if (item.optBoolean("instrumental", false)) return null
            val synced = item.optString("syncedLyrics")
            if (synced.isNotBlank()) return synced
        }
        return null
    }

    private fun requestText(url: String): String {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Lrclib-Client", CLIENT_HEADER)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "LRCLIB HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}

internal object LrcLibLyricsParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val metaTag = Regex("^\\[[a-zA-Z#]+:.*]$")

    fun toLyricsResult(raw: String): LyricsResult? {
        data class ParsedLine(val begin: Long, val text: String)

        val parsed = mutableListOf<ParsedLine>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || metaTag.matches(trimmed)) return@forEach
            val matches = timestamp.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach
            val text = trimmed.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { match ->
                val fraction = match.groupValues.getOrNull(2 + 1).orEmpty()
                val millis = when (fraction.length) {
                    1 -> fraction.toLong() * 100L
                    2 -> fraction.toLong() * 10L
                    3 -> fraction.toLong()
                    else -> 0L
                }
                parsed += ParsedLine(
                    begin = match.groupValues[1].toLong() * 60_000L +
                        match.groupValues[2].toLong() * 1_000L + millis,
                    text = text,
                )
            }
        }
        if (parsed.size < 2) return null
        val sorted = parsed.sortedBy(ParsedLine::begin).distinctBy(ParsedLine::begin)
        val originals = sorted.mapIndexed { index, line ->
            val end = sorted.getOrNull(index + 1)?.begin ?: (line.begin + 5_000L)
            LyricsLine(
                start = line.begin,
                end = maxOf(end, line.begin + 1L),
                words = listOf(LyricsWord(line.begin, maxOf(end, line.begin + 1L), line.text)),
            )
        }
        // LRCLIB 无翻译/罗马音：两者保持 null，由补翻译链路决定是否向其他源补齐。
        return LyricsResult(
            tags = mapOf("source" to "lrclib"),
            original = originals,
            translated = null,
            romanization = null,
        )
    }
}
