/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToLong

internal data class KuwoLyricsPayload(
    val raw: String,
    val source: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("raw", raw)
        put("source", source)
    }

    companion object {
        fun fromJson(json: JSONObject) = KuwoLyricsPayload(
            raw = json.getString("raw"),
            source = json.optString("source", "cache"),
        )
    }
}

internal object KuwoApiClient {
    private const val SEARCH_URL =
        "https://www.kuwo.cn/openapi/v1/www/search/searchMusicBykeyWord"
    private const val OPEN_LYRIC_URL =
        "https://www.kuwo.cn/openapi/v1/www/lyric/getlyric"
    private const val LRCX_URL = "https://newlyric.kuwo.cn/newlyric.lrc"

    fun search(track: KuwoTrackMetadata): Long? {
        val query = listOfNotNull(track.title, track.artist)
            .filter(String::isNotBlank)
            .joinToString(" ")
        if (query.isBlank()) return null
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val json = JSONObject(
            requestText("$SEARCH_URL?key=$encoded&pn=1&rn=30&httpsStatus=1"),
        )
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return null
        val candidates = buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val rid = item.optLong("rid").takeIf { it > 0L } ?: continue
                add(
                    KuwoSearchCandidate(
                        rid = rid,
                        title = item.optString("name").takeIf(String::isNotBlank),
                        artist = item.optString("artist").takeIf(String::isNotBlank),
                        album = item.optString("album").takeIf(String::isNotBlank),
                        durationSeconds = item.optLong("duration").coerceAtLeast(0L),
                    ),
                )
            }
        }
        return KuwoTrackIdResolver.chooseCandidate(track, candidates)?.rid
    }

    fun fetchLyrics(rid: Long): KuwoLyricsPayload {
        var lrcxFailure: Throwable? = null
        runCatching { fetchLrcx(rid) }
            .onSuccess { raw ->
                if (KuwoLyricsParser.parse(raw).isNotEmpty()) {
                    return KuwoLyricsPayload(raw, "lrcx")
                }
                lrcxFailure = IllegalStateException("酷我 LRCX 没有可解析歌词")
            }
            .onFailure { lrcxFailure = it }

        return runCatching {
            KuwoLyricsPayload(fetchOpenLyrics(rid), "openapi")
        }.getOrElse { fallbackFailure ->
            lrcxFailure?.let(fallbackFailure::addSuppressed)
            throw fallbackFailure
        }
    }

    private fun fetchLrcx(rid: Long): String {
        val query = KuwoLyricsResponseDecoder.buildRequestQuery(rid)
        val raw = requestBytes("$LRCX_URL?$query", userAgent = "okhttp/3.10.0")
        return KuwoLyricsResponseDecoder.decode(raw)
            ?: throw IllegalStateException("酷我 LRCX 返回无内容")
    }

    private fun fetchOpenLyrics(rid: Long): String {
        val json = JSONObject(requestText("$OPEN_LYRIC_URL?musicId=$rid&httpsStatus=1"))
        val list = json.optJSONObject("data")?.optJSONArray("lrclist")
            ?: throw IllegalStateException("酷我 OpenAPI 返回无歌词")
        val lines = buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val text = item.optString("lineLyric")
                val timeMs = (item.optString("time").toDoubleOrNull()?.times(1_000.0))
                    ?.roundToLong() ?: continue
                add("[${formatTimestamp(timeMs)}]$text")
            }
        }
        return lines.joinToString("\n").takeIf(String::isNotBlank)
            ?: throw IllegalStateException("酷我 OpenAPI 歌词为空")
    }

    private fun requestText(url: String): String =
        requestBytes(url).toString(StandardCharsets.UTF_8)

    private fun requestBytes(
        url: String,
        userAgent: String = "Mozilla/5.0",
    ): ByteArray {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", "https://www.kuwo.cn/")
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "酷我 HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun formatTimestamp(timeMs: Long): String {
        val safe = timeMs.coerceAtLeast(0L)
        return String.format(
            Locale.ROOT,
            "%02d:%02d.%03d",
            safe / 60_000L,
            safe / 1_000L % 60L,
            safe % 1_000L,
        )
    }
}
