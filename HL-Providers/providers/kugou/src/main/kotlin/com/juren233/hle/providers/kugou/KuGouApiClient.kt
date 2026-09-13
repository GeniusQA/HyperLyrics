/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

internal object KuGouApiClient {
    private const val TAG = "HLEProvider/KuGou"
    private const val SEARCH_URL = "https://lyrics.kugou.com/v2/search"
    private const val DOWNLOAD_URL = "https://lyrics.kugou.com/v2/download"
    private const val APP_ID = "1005"

    // Verified from the original KuGou 20.7.5 APK manifest (versionCode 20759).
    private const val CLIENT_VERSION = "20759"

    fun searchCandidates(track: KuGouTrackMetadata, mid: String): List<KuGouSearchCandidate> =
        KuGouSearchStrategy.rank(track) { keyword ->
            fetchCandidates(track, keyword, mid)
        }

    private fun fetchCandidates(
        track: KuGouTrackMetadata,
        keyword: String,
        mid: String,
    ): List<KuGouSearchCandidate> {
        val duration = track.durationMs.coerceAtLeast(0L) / 1_000L * 1_000L
        val parameters = mapOf(
            "album_audio_id" to (track.albumAudioId ?: 0L).toString(),
            "appid" to APP_ID,
            "clientver" to CLIENT_VERSION,
            "duration" to duration.toString(),
            "hash" to track.directHash.orEmpty(),
            "keyword" to keyword,
            "lrctxt" to "1",
            "man" to "yes",
            "query_copyright" to "1",
        )
        val json = requestJson("$SEARCH_URL?${KuGouApiProtocol.signedQuery(parameters)}", mid)
        check(json.optInt("status") == 1 && json.optInt("errcode") == 200) {
            "酷狗歌词搜索失败: errcode=${json.optInt("errcode", -1)} " +
                "errmsg=${json.optString("errmsg")}".trim()
        }
        val array = json.optJSONObject("data")?.optJSONArray("candidates")
            ?: return emptyList()
        val candidates = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val downloadId = item.optString("download_id")
                    .ifBlank { item.optString("id") }
                val accessKey = item.optString("accesskey")
                if (downloadId.isBlank() || accessKey.isBlank()) continue
                add(
                    KuGouSearchCandidate(
                        downloadId = downloadId,
                        accessKey = accessKey,
                        contentType = item.optInt("contenttype", 0),
                        title = item.optString("song").takeIf(String::isNotBlank),
                        artist = item.optString("singer").takeIf(String::isNotBlank),
                        durationMs = item.optLong("duration").coerceAtLeast(0L),
                        serverScore = item.optInt("score", 0),
                    ),
                )
            }
        }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "酷狗歌词搜索: keyword=$keyword, candidates=${candidates.size}")
        }
        return candidates
    }

    fun download(candidate: KuGouSearchCandidate, mid: String): ByteArray {
        val parameters = mapOf(
            "accesskey" to candidate.accessKey,
            "appid" to APP_ID,
            "clientver" to CLIENT_VERSION,
            "contenttype" to candidate.contentType.toString(),
            "download_id" to candidate.downloadId,
        )
        val json = requestJson("$DOWNLOAD_URL?${KuGouApiProtocol.signedQuery(parameters)}", mid)
        check(json.optInt("status") == 1 && json.optInt("error_code") == 0) {
            "酷狗歌词下载失败: error_code=${json.optInt("error_code", -1)}"
        }
        val content = json.optJSONObject("data")?.optString("content").orEmpty()
        check(content.isNotBlank()) { "酷狗歌词下载返回空内容" }
        return Base64.getDecoder().decode(content)
    }

    private fun requestJson(url: String, mid: String): JSONObject {
        check(!Thread.currentThread().isInterrupted) { "酷狗歌词请求已取消" }
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Android-KuGou/$CLIENT_VERSION")
            setRequestProperty("clienttime", System.currentTimeMillis().toString())
            setRequestProperty("mid", mid)
            setRequestProperty("dfid", "-")
            setRequestProperty("uuid", mid)
            setRequestProperty("userid", "0")
            setRequestProperty("token", "")
        }
        return try {
            val responseCode = connection.responseCode
            check(responseCode == HttpURLConnection.HTTP_OK) {
                "酷狗歌词 HTTP $responseCode"
            }
            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use {
                it.readText()
            }
            check(!Thread.currentThread().isInterrupted) { "酷狗歌词请求已取消" }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}

internal object KuGouApiProtocol {
    // Default lyric signing secret recovered from the original 20.7.5 APK and
    // verified against the live v2 search/download endpoints.
    private const val SIGNING_SECRET = "OIlwieks28dk2k092lksi2UIkp"

    fun signedQuery(parameters: Map<String, String>): String {
        val signed = parameters.toSortedMap().toMutableMap()
        signed["signature"] = signature(parameters)
        return signed.toSortedMap().entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    fun signature(parameters: Map<String, String>): String {
        val joined = parameters.toSortedMap().entries.joinToString("") { (key, value) ->
            "$key=$value"
        }
        return md5Hex("$SIGNING_SECRET$joined$SIGNING_SECRET")
    }

    fun clientMid(seed: String): String = md5Hex(seed)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
