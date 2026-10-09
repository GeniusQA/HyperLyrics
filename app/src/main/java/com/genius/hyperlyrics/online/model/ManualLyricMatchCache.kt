/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.online.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 「二次匹配」缓存：记录 (当前播放歌曲 → 用户手动选中的候选)。
 * 后续同一首歌再次播放时，hook 在在线兜底/翻译补齐开始前查此缓存并直接按缓存候选取词，
 * 免去每次手动二次匹配。
 *
 * 存储为 SharedPreferences 中的 JSON 数组（设置 App 写入 → hook 读取，
 * 与单次匹配请求 [RootConstants.KEY_HOOK_MANUAL_LYRIC_MATCH_REQUEST] 同一 App→hook 通道）。
 * 按 (currentTitle, currentArtist) 归一化去重，超出 [MAX_ENTRIES] 淘汰最旧记录。
 */
object ManualLyricMatchCache {
    /** 缓存上限：足够覆盖常听曲目，同时避免 pref 无限膨胀。 */
    const val MAX_ENTRIES = 100

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val serializer = ListSerializer(ManualLyricMatchRequest.serializer())

    /** 歌名/歌手匹配键：去首尾空白 + 小写（与 hook 侧 normalizeIdentity 一致）。 */
    fun identity(value: String?): String = value.orEmpty().trim().lowercase()

    fun decode(raw: String?): List<ManualLyricMatchRequest> {
        val value = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, value) }.getOrDefault(emptyList())
    }

    fun encode(entries: List<ManualLyricMatchRequest>): String = json.encodeToString(serializer, entries)
}
