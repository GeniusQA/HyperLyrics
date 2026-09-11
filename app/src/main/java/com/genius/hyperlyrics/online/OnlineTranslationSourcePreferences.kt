/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.online

import android.content.SharedPreferences
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.online.model.Source

object OnlineTranslationSourcePreferences {
    const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    const val QISHUI_PACKAGE = "com.luna.music"
    const val SPOTIFY_PACKAGE = "com.spotify.music"
    const val SALT_PACKAGE = "com.salt.music"
    const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"

    val defaultOrder: List<Source> = listOf(
        Source.NE,
        Source.QM,
        Source.KUWO,
        Source.KUGOU,
    )

    fun orderedSources(prefs: SharedPreferences?): List<Source> {
        val stored = prefs?.getString(
            RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
            RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
        )
        val order = resolveOrder(
            rawOrder = stored,
            automaticSelection = isAutoSelectBestSourceEnabled(prefs),
        )
        return resolveEnabledSources(order) { source -> isSourceEnabled(prefs, source) }
    }

    /** 无 SharedPreferences 场景（如 Provider Pack 进程）按键值读取解析启用来源顺序。 */
    fun orderedSources(
        rawOrder: String?,
        automaticSelection: Boolean,
        isEnabled: (Source) -> Boolean,
    ): List<Source> =
        resolveEnabledSources(resolveOrder(rawOrder, automaticSelection), isEnabled)

    fun resolveEnabledSources(
        order: List<Source>,
        isEnabled: (Source) -> Boolean,
    ): List<Source> {
        val enabledSources = order.filter(isEnabled)
        return enabledSources.ifEmpty { order.take(1) }
    }

    fun canToggleSource(source: Source, enabledSources: Collection<Source>): Boolean =
        source !in enabledSources || enabledSources.size > 1

    fun resolveOrder(rawOrder: String?, automaticSelection: Boolean): List<Source> =
        if (automaticSelection) defaultOrder else normalizeOrder(rawOrder)

    fun isAutoSelectBestSourceEnabled(prefs: SharedPreferences?): Boolean =
        prefs?.getBoolean(
            RootConstants.KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
            RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
        ) ?: RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE

    fun normalizeOrder(raw: String?): List<Source> {
        val parsed = raw.orEmpty()
            .split(',')
            .mapNotNull { value ->
                runCatching { Source.valueOf(value.trim()) }.getOrNull()
            }
            // LRCLIB 已改为内置兜底歌词源（固定殿后、无需开关），不再作为可选平台来源。
            .filter { it != Source.LRCLIB }
            .distinct()
        return parsed + defaultOrder.filterNot(parsed::contains)
    }

    fun serializeOrder(order: List<Source>): String =
        normalizeOrder(order.joinToString(",", transform = Source::name))
            .joinToString(",", transform = Source::name)

    fun sourcePreferenceKey(source: Source): String = when (source) {
        Source.NE -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE
        Source.QM -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_QQ
        Source.KUWO -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO
        Source.KUGOU -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU
        Source.LRCLIB -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_LRCLIB
        Source.LB -> RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS
    }

    fun sourceDefaultEnabled(source: Source): Boolean = when (source) {
        Source.NE -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE
        Source.QM -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_QQ
        Source.KUWO -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO
        Source.KUGOU -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU
        Source.LRCLIB -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_LRCLIB
        Source.LB -> RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS
    }

    fun isSourceEnabled(prefs: SharedPreferences?, source: Source): Boolean =
        prefs?.getBoolean(sourcePreferenceKey(source), sourceDefaultEnabled(source))
            ?: sourceDefaultEnabled(source)

    fun appPreferenceKey(packageName: String): String? = when (packageName) {
        APPLE_MUSIC_PACKAGE -> RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION
        QISHUI_PACKAGE -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_QISHUI
        SPOTIFY_PACKAGE -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY
        SALT_PACKAGE -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_SALT
        YOUTUBE_MUSIC_PACKAGE -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_YOUTUBE_MUSIC
        else -> null
    }

    fun appDefaultEnabled(packageName: String): Boolean = when (packageName) {
        APPLE_MUSIC_PACKAGE -> RootConstants.DEFAULT_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION
        QISHUI_PACKAGE -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_QISHUI
        SPOTIFY_PACKAGE -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY
        SALT_PACKAGE -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_SALT
        YOUTUBE_MUSIC_PACKAGE -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_YOUTUBE_MUSIC
        else -> false
    }

    fun isAppEnabled(prefs: SharedPreferences?, packageName: String?): Boolean {
        val packageValue = packageName ?: return false
        val key = appPreferenceKey(packageValue) ?: return false
        return prefs?.getBoolean(key, appDefaultEnabled(packageValue))
            ?: appDefaultEnabled(packageValue)
    }

    fun isSourcePreference(key: String?): Boolean = key in setOf(
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_QQ,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_LRCLIB,
    )

    fun isAppPreference(key: String?): Boolean = key in setOf(
        RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_QISHUI,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_SALT,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_YOUTUBE_MUSIC,
    )
}
