package com.juren233.hyperlyricsenhanced.ui.component

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.display.FONT_COLOR_MODE_COVER
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.display.FONT_COLOR_MODE_COVER_GRADIENT
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.display.FONT_COLOR_MODE_CUSTOM
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.display.FONT_COLOR_MODE_MONET
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.display.resolveFontColorMode
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/**
 * 「字体颜色」设置卡片：与摘要态歌词同款交互，但偏好 key 按歌词位置独立
 * （[keys] 决定读写哪一组），各位置互不影响。
 */
@Composable
fun FontColorModeCard(
    prefs: SharedPreferences,
    saveConfig: (String, Any) -> Unit,
    keys: RootConstants.FontColorKeys,
) {
    var fontColorMode by remember {
        mutableIntStateOf(
            resolveFontColorMode(
                customEnabled = prefs.getBoolean(
                    keys.customEnabled,
                    RootConstants.DEFAULT_HOOK_CUSTOM_TEXT_COLOR_ENABLED
                ),
                monetEnabled = prefs.getBoolean(
                    keys.monet,
                    RootConstants.DEFAULT_HOOK_MONET_TEXT_COLOR
                ),
                coverEnabled = prefs.getBoolean(
                    keys.coverColor,
                    RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_COLOR
                ),
                coverGradient = prefs.getBoolean(
                    keys.coverGradient,
                    RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_GRADIENT
                ),
            )
        )
    }
    var customFontColor by remember {
        mutableIntStateOf(
            prefs.getInt(
                keys.customColor,
                RootConstants.DEFAULT_HOOK_CUSTOM_TEXT_COLOR
            )
        )
    }
    var showColorPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth()
    ) {
        Column {
            val fontColorOptions = listOf(
                stringResource(id = R.string.option_font_color_default),
                stringResource(id = R.string.option_font_color_monet),
                stringResource(id = R.string.option_font_color_cover),
                stringResource(id = R.string.option_font_color_cover_gradient),
                stringResource(id = R.string.option_font_color_custom),
            )
            OverlayDropdownPreference(
                title = stringResource(id = R.string.title_font_color),
                items = fontColorOptions,
                selectedIndex = fontColorMode.coerceIn(0, fontColorOptions.lastIndex),
                onSelectedIndexChange = { mode ->
                    fontColorMode = mode
                    when (mode) {
                        FONT_COLOR_MODE_MONET -> {
                            saveConfig(keys.monet, true)
                            saveConfig(keys.coverColor, false)
                            saveConfig(keys.coverGradient, false)
                            saveConfig(keys.customEnabled, false)
                        }
                        FONT_COLOR_MODE_COVER -> {
                            saveConfig(keys.monet, false)
                            saveConfig(keys.coverGradient, false)
                            saveConfig(keys.coverColor, true)
                            saveConfig(keys.customEnabled, false)
                        }
                        FONT_COLOR_MODE_COVER_GRADIENT -> {
                            saveConfig(keys.monet, false)
                            saveConfig(keys.coverGradient, true)
                            saveConfig(keys.coverColor, true)
                            saveConfig(keys.customEnabled, false)
                        }
                        FONT_COLOR_MODE_CUSTOM -> {
                            saveConfig(keys.monet, false)
                            saveConfig(keys.customEnabled, true)
                            saveConfig(keys.coverColor, false)
                        }
                        else -> {
                            saveConfig(keys.monet, false)
                            saveConfig(keys.coverColor, false)
                            saveConfig(keys.coverGradient, false)
                            saveConfig(keys.customEnabled, false)
                        }
                    }
                }
            )
            AnimatedVisibility(
                visible = fontColorMode == FONT_COLOR_MODE_CUSTOM,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                BasicComponent(
                    title = stringResource(R.string.title_custom_font_color),
                    onClick = { showColorPicker = true },
                    endActions = {
                        CustomFontColorPreview(color = customFontColor)
                    },
                )
            }
        }
    }
    if (showColorPicker) {
        CustomFontColorPickerDialog(
            show = true,
            initialColor = customFontColor,
            onDismiss = { showColorPicker = false },
            onConfirm = { color ->
                customFontColor = color
                saveConfig(keys.customColor, color)
                showColorPicker = false
            },
        )
    }
}
