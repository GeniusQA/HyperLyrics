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
import com.genius.hyperlyrics.root.RootApplication
import com.genius.hyperlyrics.ui.component.EnhancedVersionNotice
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

fun LazyListScope.homePageSections(
    availableUpdateVersion: String?,
    enableSuperIsland: Boolean,
    onSuperIslandToggle: (Boolean) -> Unit,
    enableDynamicIsland: Boolean,
    onDynamicIslandToggle: (Boolean) -> Unit,
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
    val isModuleActive = RootApplication.xposedService != null

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
                    enabled = isModuleActive,
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
                    title = stringResource(R.string.title_dynamic_island_lyrics),
                    summary = stringResource(R.string.summary_dynamic_island_lyrics),
                    checked = enableDynamicIsland,
                    onCheckedChange = onDynamicIslandToggle,
                    enabled = !isModuleActive,
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
                    enabled = isModuleActive,
                )
                SwitchPreference(
                    title = stringResource(R.string.title_remove_island_whitelist),
                    checked = removeIslandWhitelist,
                    onCheckedChange = onRemoveIslandWhitelistToggle,
                    enabled = isModuleActive,
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
