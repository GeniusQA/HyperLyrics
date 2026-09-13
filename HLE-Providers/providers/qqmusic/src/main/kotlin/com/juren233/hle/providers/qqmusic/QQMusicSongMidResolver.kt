/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.util.Log
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 小米音乐（com.miui.player）的 MediaSession MEDIA_ID 上报的是 QQ 音乐 songmid，而
 * lyric_download.fcg 只接受数字歌曲 ID。已对照原包 4.44.0.9 DEX 与在线接口验证：
 * musicid=songmid 返回空内容（musicid="0"），数字 ID 返回完整 QRC。
 * 本对象把非数字 ID 经 QQ 单曲信息接口（songmid 精确主键查询，非模糊搜索）
 * 换算为数字歌曲 ID；数字 ID（QQ 手机版/HD 的 MediaSession 上报值）原样透传。
 */
internal object QQMusicSongMidResolver {
    private const val TAG = "HLEProvider/QQMusic"
    private const val SINGLE_SONG_URL =
        "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
    private const val REQUEST_TIMEOUT_MS = 15_000

    private val numericPattern = Regex("""^\d+$""")

    private val resolved = ConcurrentHashMap<String, String>()

    fun isNumericSongId(id: String): Boolean = numericPattern.matches(id)

    fun resolveNumericSongId(id: String): String {
        if (isNumericSongId(id)) return id
        resolved[id]?.let { return it }
        val songId = fetchNumericSongId(id)
            ?: throw IllegalStateException("QQ 歌曲 ID 换算失败: mid=$id")
        resolved[id] = songId
        Log.i(TAG, "QQ 歌曲 ID 已换算: mid=$id songId=$songId")
        return songId
    }

    private fun fetchNumericSongId(songMid: String): String? {
        val connection = (URI(SINGLE_SONG_URL).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://y.qq.com/")
        }
        return try {
            connection.getInputStream().bufferedReader().use { reader ->
                parseSingleSongResponse(reader.readText())
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseSingleSongResponse(raw: String): String? = runCatching {
        val root = org.json.JSONObject(raw)
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        // 响应里 album.id 等嵌套 id 出现在歌曲 id 之前，必须取 data[0] 的顶层 id 字段
        val songId = data.getJSONObject(0).optLong("id", -1L)
        if (songId <= 0L) null else songId.toString()
    }.getOrNull()
}
