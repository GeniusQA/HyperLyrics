package com.genius.hyperlyrics.ui.page.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.genius.hyperlyrics.R
import com.genius.hyperlyrics.ui.component.EnhancedVersionNotice
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

fun LazyListScope.homePageSections(
    // LSPosed 服务绑定状态（组合安全的响应式值），由 @Composable 调用方收集后传入，
    // 不可在本函数体内用 collectAsState() 收集（homePageSections 非 @Composable）。
    isModuleActive: Boolean,
    availableUpdateVersion: String?,
    enableSuperIsland: Boolean,
    onSuperIslandToggle: (Boolean) -> Unit,
    enableDynamicIsland: Boolean,
    onDynamicIslandToggle: (Boolean) -> Unit,
    enableLsposedSuperIsland: Boolean,
    onLsposedSuperIslandToggle: (Boolean) -> Unit,
    enableAodLyrics: Boolean,
    onAodLyricsToggle: (Boolean) -> Unit,
    enableLockScreenLyrics: Boolean,
    onLockScreenLyricsToggle: (Boolean) -> Unit,
    onSuperIslandConfigClick: () -> Unit,
    onMediaCardConfigClick: () -> Unit,
    onDynamicIslandConfigClick: () -> Unit,
    onLockScreenAodConfigClick: () -> Unit,
    onLockScreenLyricsConfigClick: () -> Unit,
    onClassicAodConfigClick: () -> Unit,
    onLyricSettingsClick: () -> Unit,
    removeFocusWhitelist: Boolean,
    onRemoveFocusWhitelistToggle: (Boolean) -> Unit,
    removeIslandWhitelist: Boolean,
    onRemoveIslandWhitelistToggle: (Boolean) -> Unit,
    onAppSettingsClick: () -> Unit,
) {
    // Root / LSPosed 模式下通知型灵动岛歌词（无 root 方案）不可用；
    // 非 Root 模式下 SystemUI 增强类功能不可用。
    // 注意：isModuleActive 必须收集响应式绑定状态。直接读 RootApplication.xposedService
    // 会在服务绑定晚于首帧时把本应可用的开关误置灰，且绑定完成不会触发重组恢复。

    item(key = "enhanced_version_notice") {
        EnhancedVersionNotice(
            updateAvailable = availableUpdateVersion != null,
            modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()
        )
    }

    item(key = "basic_features_title") {
        SmallTitle(
            text = stringResource(R.string.title_basic_features)
        )
    }

    item(key = "basic_features_content_super_island") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(R.string.title_lyric_settings),
                onClick = onLyricSettingsClick,
            )
        }
    }

    item(key = "basic_features_content_system_ui") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.title_miui_systemui_enhancement),
                    summary = stringResource(R.string.summary_miui_systemui_enhancement),
                    checked = enableSuperIsland,
                    onCheckedChange = onSuperIslandToggle,
                    enabled = isModuleActive && enableLsposedSuperIsland,
                )
                AnimatedVisibility(visible = enableSuperIsland) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.title_super_island_lyrics_config),
                            onClick = onSuperIslandConfigClick,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.title_media_cards),
                            onClick = onMediaCardConfigClick,
                        )
                    }
                }
            }
        }
    }

    item(key = "basic_features_content_aod_lyrics") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.title_aod_lyrics),
                    summary = stringResource(R.string.summary_aod_lyrics),
                    checked = enableAodLyrics,
                    onCheckedChange = onAodLyricsToggle,
                    enabled = isModuleActive && enableLsposedSuperIsland,
                )
                AnimatedVisibility(visible = enableAodLyrics) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.title_lock_screen_aod),
                            onClick = onLockScreenAodConfigClick,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.title_classic_aod),
                            onClick = onClassicAodConfigClick,
                        )
                    }
                }
                SwitchPreference(
                    title = stringResource(R.string.title_lock_screen_lyrics),
                    summary = stringResource(R.string.summary_lock_screen_lyrics),
                    checked = enableLockScreenLyrics,
                    onCheckedChange = onLockScreenLyricsToggle,
                    enabled = isModuleActive && enableLsposedSuperIsland,
                )
                AnimatedVisibility(visible = enableLockScreenLyrics) {
                    ArrowPreference(
                        title = stringResource(R.string.title_lock_screen_lyrics_config),
                        onClick = onLockScreenLyricsConfigClick,
                    )
                }
            }
        }
    }

    item(key = "basic_features_content_dynamic_island") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.title_lsposed_super_island_lyrics),
                    summary = stringResource(R.string.summary_lsposed_super_island_lyrics),
                    checked = enableLsposedSuperIsland,
                    onCheckedChange = { isChecked ->
                        // 互斥：开启 LSPosed 超级岛歌词时，先关闭通知型超级岛歌词。
                        if (isChecked && enableDynamicIsland) {
                            onDynamicIslandToggle(false)
                        }
                        onLsposedSuperIslandToggle(isChecked)
                    },
                    enabled = !enableDynamicIsland,
                )
                SwitchPreference(
                    title = stringResource(R.string.title_dynamic_island_lyrics),
                    summary = stringResource(R.string.summary_dynamic_island_lyrics),
                    checked = enableDynamicIsland,
                    onCheckedChange = { isChecked ->
                        // 互斥：开启通知型超级岛歌词时，先关闭 LSPosed 超级岛歌词。
                        if (isChecked && enableLsposedSuperIsland) {
                            onLsposedSuperIslandToggle(false)
                        }
                        onDynamicIslandToggle(isChecked)
                    },
                    enabled = !enableLsposedSuperIsland,
                )
                AnimatedVisibility(visible = enableDynamicIsland) {
                    ArrowPreference(
                        title = stringResource(R.string.title_dynamic_island_config),
                        onClick = onDynamicIslandConfigClick,
                    )
                }
            }
        }
    }

    item(key = "special_features_title") {
        SmallTitle(
            text = stringResource(R.string.title_special_features)
        )
    }

    item(key = "special_features_content") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.title_remove_focus_whitelist),
                    summary = stringResource(R.string.summary_remove_focus_whitelist),
                    checked = removeFocusWhitelist,
                    onCheckedChange = onRemoveFocusWhitelistToggle,
                    enabled = isModuleActive && enableLsposedSuperIsland,
                )
                SwitchPreference(
                    title = stringResource(R.string.title_remove_island_whitelist),
                    checked = removeIslandWhitelist,
                    onCheckedChange = onRemoveIslandWhitelistToggle,
                    enabled = isModuleActive && enableLsposedSuperIsland,
                )
            }
        }
    }

    item(key = "app_settings") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(R.string.title_app_settings),
                summary = stringResource(R.string.summary_app_settings),
                onClick = onAppSettingsClick,
            )
        }
    }
}
