/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.genius.hyperlyrics.common.PrefsBridge
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.common.UIConstants
import com.genius.hyperlyrics.online.model.ManualLyricMatchCache
import com.genius.hyperlyrics.online.model.ManualLyricMatchRequest

/**
 * 接收 hook（SystemUI）回传的「二次匹配」结果：
 * - 成功（已取到歌词/翻译）才把 (当前歌曲 → 候选) 写入缓存，供后续同曲自动复用；
 * - 成功/失败均弹 toast 提示。
 *
 * 复用 [LyricOriginReceiver] 的广播模式：hook 写入不持久化，故回传 App 由本 Receiver 落地。
 * 缓存需被 hook 读取，因此本地偏好写入的同时通过 [PrefsBridge] 推给 hook 的远程偏好。
 */
class ManualMatchResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RootConstants.ACTION_MANUAL_MATCH_RESULT) return
        val success = intent.getBooleanExtra(RootConstants.EXTRA_MANUAL_MATCH_SUCCESS, false)
        val message = intent.getStringExtra(RootConstants.EXTRA_MANUAL_MATCH_MESSAGE)
            ?: if (success) "二次匹配成功" else "二次匹配失败"

        if (success) {
            runCatching {
                val request = ManualLyricMatchRequest(
                    currentTitle = intent.getStringExtra(RootConstants.EXTRA_MANUAL_MATCH_CURRENT_TITLE) ?: "",
                    currentArtist = intent.getStringExtra(RootConstants.EXTRA_MANUAL_MATCH_CURRENT_ARTIST) ?: "",
                    title = intent.getStringExtra(RootConstants.EXTRA_MANUAL_MATCH_TITLE) ?: "",
                    artist = intent.getStringExtra(RootConstants.EXTRA_MANUAL_MATCH_ARTIST) ?: "",
                    album = intent.getStringExtra(RootConstants.EXTRA_MANUAL_MATCH_ALBUM) ?: "",
                    source = intent.getStringExtra(RootConstants.EXTRA_MANUAL_MATCH_SOURCE) ?: "",
                    sourceSongId = intent.getStringExtra(RootConstants.EXTRA_MANUAL_MATCH_SONG_ID) ?: "",
                    durationMs = intent.getLongExtra(RootConstants.EXTRA_MANUAL_MATCH_DURATION, 0L),
                    requestedAtMs = System.currentTimeMillis(),
                )
                val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
                val identity = ManualLyricMatchCache::identity
                val entries = ManualLyricMatchCache
                    .decode(prefs.getString(RootConstants.KEY_HOOK_MANUAL_MATCH_CACHE, null))
                    .toMutableList()
                entries.removeAll {
                    identity(it.currentTitle) == identity(request.currentTitle) &&
                        identity(it.currentArtist) == identity(request.currentArtist)
                }
                entries.add(request)
                while (entries.size > ManualLyricMatchCache.MAX_ENTRIES) entries.removeAt(0)
                val encoded = ManualLyricMatchCache.encode(entries)
                prefs.edit().putString(RootConstants.KEY_HOOK_MANUAL_MATCH_CACHE, encoded).apply()
                // 推给 hook 远程偏好，供在线兜底/翻译补齐时读取命中。
                PrefsBridge.putString(RootConstants.KEY_HOOK_MANUAL_MATCH_CACHE, encoded)
            }
        }

        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
