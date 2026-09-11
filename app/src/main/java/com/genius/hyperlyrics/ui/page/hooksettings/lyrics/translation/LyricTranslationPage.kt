package com.genius.hyperlyrics.ui.page.hooksettings.lyrics.translation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.genius.hyperlyrics.R
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.common.lyric.AdjacentTranslationPolicy
import com.genius.hyperlyrics.root.utils.TranslationHelper
import com.genius.hyperlyrics.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.genius.hyperlyrics.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.genius.hyperlyrics.ui.page.hooksettings.lyrics.common.rememberHookPrefs

@Composable
fun LyricTranslationPage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)

    val lyricMode by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_LYRIC_MODE,
                RootConstants.DEFAULT_HOOK_LYRIC_MODE,
            )
        )
    }
    val islandContentLeft by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT,
                RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT,
            )
        )
    }
    val islandContentRight by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT,
                RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_RIGHT,
            )
        )
    }
    var translationDisplayMode by remember {
        mutableIntStateOf(TranslationHelper.getTranslationDisplayMode(prefs))
    }
    var translationFallback by remember {
        mutableStateOf(TranslationHelper.isTranslationFallback(prefs))
    }
    var translationOnly by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_TRANSLATION_ONLY,
                RootConstants.DEFAULT_HOOK_TRANSLATION_ONLY,
            )
        )
    }
    var swapTranslation by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_SWAP_TRANSLATION,
                RootConstants.DEFAULT_HOOK_SWAP_TRANSLATION,
            )
        )
    }
    var adjacentBackgroundTranslation by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_ADJACENT_BACKGROUND_TRANSLATION,
                RootConstants.DEFAULT_HOOK_ADJACENT_BACKGROUND_TRANSLATION,
            )
        )
    }

    XposedLyricSettingPage(title = stringResource(R.string.title_translation)) {
        translationSections(
            adjacentBackgroundTranslationAvailable = AdjacentTranslationPolicy.isEligible(
                lyricMode,
                islandContentLeft,
                islandContentRight,
            ),
            translationDisplayMode = translationDisplayMode,
            onTranslationDisplayModeChange = {
                translationDisplayMode = it
                saveConfig(RootConstants.KEY_HOOK_TRANSLATION_DISPLAY, it)
            },
            translationFallback = translationFallback,
            onTranslationFallbackChange = {
                translationFallback = it
                saveConfig(RootConstants.KEY_HOOK_TRANSLATION_FALLBACK, it)
            },
            translationOnly = translationOnly,
            onTranslationOnlyChange = {
                translationOnly = it
                saveConfig(RootConstants.KEY_HOOK_TRANSLATION_ONLY, it)
                if (it && swapTranslation) {
                    swapTranslation = false
                    saveConfig(RootConstants.KEY_HOOK_SWAP_TRANSLATION, false)
                }
            },
            swapTranslation = swapTranslation,
            onSwapTranslationChange = {
                swapTranslation = it
                saveConfig(RootConstants.KEY_HOOK_SWAP_TRANSLATION, it)
                if (it && translationOnly) {
                    translationOnly = false
                    saveConfig(RootConstants.KEY_HOOK_TRANSLATION_ONLY, false)
                }
            },
            adjacentBackgroundTranslation = adjacentBackgroundTranslation,
            onAdjacentBackgroundTranslationChange = {
                adjacentBackgroundTranslation = it
                saveConfig(RootConstants.KEY_HOOK_ADJACENT_BACKGROUND_TRANSLATION, it)
            },
        )
    }
}
