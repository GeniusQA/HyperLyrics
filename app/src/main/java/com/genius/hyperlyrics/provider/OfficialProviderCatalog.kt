/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.provider

import com.genius.hyperlyrics.provider.ytmusic.YoutubeMusicProviderPlugin

object OfficialProviderCatalog {
    const val PLUGIN_API_VERSION = 3
    const val CORE_PACKAGE_NAME = "com.genius.hyperlyrics"
    const val APPLE_MUSIC_PACKAGE_NAME = "com.apple.android.music"
    const val SALT_PLAYER_PACKAGE_NAME = "com.salt.music"
    const val YOUTUBE_MUSIC_PACKAGE_NAME = "com.google.android.apps.youtube.music"
    /**
     * 官方 Provider 的包名前缀。
     *
     * 直接沿用插件包已编译进 DEX 的命名空间作为唯一官方命名空间：
     * 插件包内的 providerPackageName 与入口类均为编译期写死，无法在不反编译重打包的
     * 前提下改名，因此不再做新旧双兼容。
     */
    const val OFFICIAL_PROVIDER_PACKAGE_PREFIX =
        "com.juren233.hyperlyricsenhanced.provider."

    /**
     * 运行时 Lyricon Provider 包名前缀（Provider Pack 内编译期写死）。
     *
     * 本分支把 Provider 运行时包名统一为 com.genius.hyperlyrics.provider.*，
     * 与清单里历史 providerPackageName 前缀并存；两者都必须被识别为官方来源，
     * 否则 ActivePlayerCoordinator 会把官方包当作 LEGACY_APK 丢弃。
     */
    const val OFFICIAL_PROVIDER_RUNTIME_PACKAGE_PREFIX =
        "com.genius.hyperlyrics.provider."

    private val OFFICIAL_PROVIDER_PACKAGE_PREFIXES = listOf(
        OFFICIAL_PROVIDER_PACKAGE_PREFIX,
        OFFICIAL_PROVIDER_RUNTIME_PACKAGE_PREFIX,
    )

    /** 从运行时 Provider 包名解析插件 id；非官方前缀返回 null。 */
    fun officialProviderId(providerPackageName: String): String? =
        OFFICIAL_PROVIDER_PACKAGE_PREFIXES.firstNotNullOfOrNull { prefix ->
            providerPackageName
                .takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.takeIf { it.isNotBlank() }
        }

    /** 官方 Provider 插件入口类所在命名空间（同样沿用插件编译期写死的包名）。 */
    const val OFFICIAL_PROVIDER_ENTRY_PREFIX = "com.juren233.hle.providers."

    data class Definition(
        val id: String,
        val displayName: String,
        val targetPackages: Set<String>,
        val secondaryProcesses: Set<String> = emptySet(),
        val description: String? = null,
        val targetDisplayNames: Map<String, String> = emptyMap(),
        val systemMediaRuntime: Boolean = false,
        val supportsNextTrackPreview: Boolean = true,
        val showInDownloadList: Boolean = true,
        /**
         * Providers shipped inside the core APK. They skip the Pack download, installation
         * record and signature verification, and are enabled by default.
         */
        val builtin: Boolean = false,
    ) {
        fun displayNameForPackage(packageName: String): String =
            targetDisplayNames[packageName] ?: displayName
    }

    val definitions = listOf(
        Definition("netease", "网易云音乐", setOf("com.netease.cloudmusic", "com.hihonor.cloudmusic")),
        Definition(
            id = "qqmusic",
            displayName = "QQ音乐",
            targetPackages = setOf("com.tencent.qqmusic", "com.tencent.qqmusicpad"),
            secondaryProcesses = setOf("com.tencent.qqmusic:QQPlayerService"),
            targetDisplayNames = mapOf(
                "com.tencent.qqmusicpad" to "QQ音乐HD",
            ),
        ),
        Definition(
            id = "kugou",
            displayName = "酷狗音乐",
            targetPackages = setOf("com.kugou.android", "com.kugou.android.lite"),
            secondaryProcesses = setOf(
                "com.kugou.android.support",
                "com.kugou.android.lite.support",
            ),
            targetDisplayNames = mapOf(
                "com.kugou.android.lite" to "酷狗概念版",
            ),
        ),
        Definition("kuwo", "酷我音乐", setOf("cn.kuwo.player")),
        Definition("spotify", "Spotify", setOf("com.spotify.music")),
        Definition(
            "lxmusic",
            "LX音乐",
            setOf(
                "cn.toside.music.mobile",
                "com.ikunshare.music.mobile",
                "com.lxnetease.music.mobile",
            ),
            showInDownloadList = false,
        ),
        Definition(
            "poweramp",
            "Poweramp",
            setOf("com.maxmpz.audioplayer"),
            showInDownloadList = false,
        ),
        Definition(
            id = "salt-player",
            displayName = "椒盐音乐",
            targetPackages = setOf("com.salt.music"),
        ),
        Definition(
            id = "qishui",
            displayName = "汽水音乐",
            targetPackages = setOf("com.luna.music"),
            systemMediaRuntime = true,
            supportsNextTrackPreview = false,
        ),
        Definition(
            "musicfree",
            "MusicFree",
            setOf("fun.upup.musicfree"),
            showInDownloadList = false,
        ),
        Definition(
            "gramophone",
            "Gramophone",
            setOf("org.akanework.gramophone"),
            showInDownloadList = false,
        ),
        Definition(
            "symfonium",
            "Symfonium",
            setOf("app.symfonik.music.player"),
            showInDownloadList = false,
        ),
        Definition(
            id = "youtube-music",
            displayName = "YouTube Music",
            targetPackages = setOf(YOUTUBE_MUSIC_PACKAGE_NAME),
            showInDownloadList = false,
            builtin = true,
        ),
    )

    private val definitionsByPackage = buildMap {
        definitions.forEach { definition ->
            definition.targetPackages.forEach { put(it, definition) }
        }
    }

    fun definitionForPackage(packageName: String): Definition? =
        definitionsByPackage[packageName]

    fun definitionForId(id: String): Definition? =
        definitions.firstOrNull { it.id == id }

    fun shouldShowInDownloadList(pluginId: String): Boolean =
        definitionForId(pluginId)?.showInDownloadList == true

    /**
     * Returns the in-APK implementation for a built-in provider, or null when the plugin has to
     * be downloaded as a signed Provider Pack.
     */
    fun builtinPlugin(pluginId: String): OfficialProviderPlugin? =
        when (pluginId) {
            "youtube-music" -> YoutubeMusicProviderPlugin()
            else -> null
        }

    fun isOfficialProviderPair(
        providerPackageName: String,
        playerPackageName: String,
    ): Boolean {
        val pluginId = officialProviderId(providerPackageName) ?: return false
        return definitionForId(pluginId)?.targetPackages?.contains(playerPackageName) == true
    }

    fun shouldLoadIntoProcess(packageName: String, processName: String): Boolean {
        if (definitionForPackage(packageName)?.systemMediaRuntime == true) return false
        if (processName == packageName) return true
        return processName in definitionForPackage(packageName)?.secondaryProcesses.orEmpty()
    }

    fun supportsNextTrackPreview(packageName: String): Boolean =
        definitionForPackage(packageName)?.supportsNextTrackPreview ?: true

    fun enabledKey(pluginId: String) =
        "key_official_provider_enabled_$pluginId"

    fun activeFileKey(packageName: String) =
        "key_official_provider_active_file_$packageName"

    fun installedVersionKey(pluginId: String) =
        "key_official_provider_installed_version_$pluginId"

    fun installedVersionNameKey(pluginId: String) =
        "key_official_provider_installed_version_name_$pluginId"

    fun remoteFileName(pluginId: String, versionCode: Int) =
        "hle-provider-$pluginId-$versionCode.hlp"
}
