/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.online.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 「二次匹配」请求：设置页手动搜索并选中候选后，通过 SharedPreferences 传给 hook 进程，
 * 由 hook 按用户指定的来源与歌曲 ID 重新抓取歌词/翻译并发布。
 *
 * @property currentTitle 当前播放歌曲的标题（用于校验请求是否仍作用于同一首歌）
 * @property currentArtist 当前播放歌曲的歌手
 * @property title 用户输入/候选的标题
 * @property artist 用户输入/候选的歌手
 * @property album 用户输入/候选的专辑（可为空）
 * @property source 候选来源（[Source.name]）
 * @property sourceSongId 候选来源中的歌曲 ID
 * @property durationMs 候选歌曲时长（毫秒）
 * @property requestedAtMs 请求时间戳
 */
@Serializable
data class ManualLyricMatchRequest(
    val currentTitle: String = "",
    val currentArtist: String = "",
    val title: String,
    val artist: String,
    val album: String = "",
    val source: String,
    val sourceSongId: String,
    val durationMs: Long = 0L,
    val requestedAtMs: Long = 0L,
) {
    val sourceEnum: Source?
        get() = runCatching { Source.valueOf(source) }.getOrNull()

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun encode(request: ManualLyricMatchRequest): String = json.encodeToString(request)

        fun decode(raw: String?): ManualLyricMatchRequest? {
            val value = raw?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { json.decodeFromString<ManualLyricMatchRequest>(value) }.getOrNull()
        }
    }
}
