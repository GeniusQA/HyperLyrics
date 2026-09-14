/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import com.genius.hyperlyrics.provider.OfficialProviderCatalog
import io.github.proify.lyricon.provider.ProviderInfo

/**
 * 原生歌词源优先级档（数值越大越优先，Central 仲裁按 rank 比较）。
 *
 * - BUILT_IN       内置插件（Apple / YouTube），不可移除
 * - OFFICIAL_PLUGIN 官方插件（.hlp Provider 包，可下载/移除）
 * - STANDALONE_MODULE 外置 Provider 模块（跳原 GitHub 仓库下载安装的另一类原生 Provider 包，
 *   与官方插件并列但渠道不同）
 * - LEGACY_APK     通用兜底（universal），仅在无官方/外置模块时作为一级原生源
 */
internal enum class ProviderSourcePriority(val rank: Int) {
    LEGACY_APK(0),
    STANDALONE_MODULE(1),
    OFFICIAL_PLUGIN(2),
    BUILT_IN(3),
}

internal object ProviderSourcePriorityResolver {
    fun resolve(providerInfo: ProviderInfo): ProviderSourcePriority =
        resolve(providerInfo.providerPackageName, providerInfo.playerPackageName)

    fun resolve(
        providerPackageName: String,
        playerPackageName: String,
    ): ProviderSourcePriority = when {
        providerPackageName == OfficialProviderCatalog.CORE_PACKAGE_NAME &&
            playerPackageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME ->
            ProviderSourcePriority.BUILT_IN
        providerPackageName == OfficialProviderCatalog.CORE_PACKAGE_NAME &&
            playerPackageName == OfficialProviderCatalog.YOUTUBE_MUSIC_PACKAGE_NAME ->
            ProviderSourcePriority.BUILT_IN
        providerPackageName == OfficialProviderCatalog.SALT_PLAYER_PACKAGE_NAME &&
            playerPackageName == OfficialProviderCatalog.SALT_PLAYER_PACKAGE_NAME ->
            ProviderSourcePriority.BUILT_IN
        OfficialProviderCatalog.isOfficialProviderPair(providerPackageName, playerPackageName) ->
            ProviderSourcePriority.OFFICIAL_PLUGIN
        providerPackageName.startsWith(OfficialProviderCatalog.STANDALONE_PROVIDER_PACKAGE_PREFIX) ->
            ProviderSourcePriority.STANDALONE_MODULE
        else -> ProviderSourcePriority.LEGACY_APK
    }
}
