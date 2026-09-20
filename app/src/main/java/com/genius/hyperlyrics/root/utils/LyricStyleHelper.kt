package com.genius.hyperlyrics.root.utils

import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.common.color.CoverGradientTextContrastOptimizer
import com.genius.hyperlyrics.common.color.CoverTextGradientPaletteOptimizer
import com.genius.hyperlyrics.root.island.IslandRuntimePreferenceReader
import com.genius.hyperlyrics.lyric.view.Highlight
import com.genius.hyperlyrics.lyric.view.LyricViewStyle
import com.genius.hyperlyrics.lyric.view.Marquee
import com.genius.hyperlyrics.lyric.view.TextLook
import com.genius.hyperlyrics.lyric.view.TitleSlot
import com.genius.hyperlyrics.lyric.view.WordMotion

/**
 * 歌词样式构建助手
 * 负责根据用户配置和歌曲信息（如封面）生成 RichLyricLineView 所需的样式对象
 */
object LyricStyleHelper {

    internal enum class FallbackReason {
        SETTING_DISABLED,
        NO_ARTWORK_OR_CACHE
    }

    internal data class ColorResolution(
        val useMonetColor: Boolean,
        val useCoverColor: Boolean,
        val useCoverGradient: Boolean,
        val paletteSource: CoverColorHelper.PaletteSource?,
        val requestedKey: String?,
        val resolvedKey: String?,
        val artworkSignature: Int?,
        val contrastAdjustment: CoverGradientTextContrastOptimizer.Adjustment?,
        val fallbackReason: FallbackReason?,
        val primaryColors: IntArray,
        val backgroundColors: IntArray,
        val highlightColors: IntArray
    ) {
        val usesDefaultColors: Boolean
            get() = fallbackReason != null
    }

    internal data class CustomTextColorPalette(
        val primary: IntArray,
        val background: IntArray,
        val highlight: IntArray,
    )

    internal fun customTextColorPalette(color: Int): CustomTextColorPalette {
        val alpha = (color ushr 24) and 0xFF
        val backgroundAlpha = alpha * 3 / 4
        val backgroundColor = (backgroundAlpha shl 24) or (color and 0x00FFFFFF)
        return CustomTextColorPalette(
            primary = intArrayOf(color),
            background = intArrayOf(backgroundColor),
            highlight = intArrayOf(color),
        )
    }

    internal fun monetTextColorPalette(color: Int): CustomTextColorPalette =
        customTextColorPalette(color)

    internal data class StyleBuildResult(
        val style: LyricViewStyle,
        val colorResolution: ColorResolution
    )

    /**
     * 构建歌词样式对象
     */
    fun buildStyle(
        prefs: SharedPreferences,
        res: Resources,
        mode: Int,
        albumBitmap: Bitmap? = null,
        mediaColorKey: String? = CoverColorHelper.currentMediaKey(),
        fontColorKeys: RootConstants.FontColorKeys = RootConstants.FONT_COLOR_KEYS_SUMMARY,
    ): LyricViewStyle = buildStyleWithDiagnostics(
        prefs = prefs,
        res = res,
        mode = mode,
        albumBitmap = albumBitmap,
        mediaColorKey = mediaColorKey,
        fontColorKeys = fontColorKeys,
    ).style

    /** 按指定位置的字体颜色 key 组构建样式（非摘要态歌词渲染用）。 */
    fun buildStyleWithFontColorKeys(
        prefs: SharedPreferences,
        res: Resources,
        mode: Int,
        keys: RootConstants.FontColorKeys,
        albumBitmap: Bitmap? = null,
        mediaColorKey: String? = CoverColorHelper.currentMediaKey(),
    ): LyricViewStyle = buildStyleWithDiagnostics(
        prefs = prefs,
        res = res,
        mode = mode,
        albumBitmap = albumBitmap,
        mediaColorKey = mediaColorKey,
        fontColorKeys = keys,
    ).style

    internal fun buildStyleWithDiagnostics(
        prefs: SharedPreferences,
        res: Resources,
        mode: Int,
        albumBitmap: Bitmap? = null,
        mediaColorKey: String? = CoverColorHelper.currentMediaKey(),
        fontColorKeys: RootConstants.FontColorKeys = RootConstants.FONT_COLOR_KEYS_SUMMARY,
    ): StyleBuildResult {
        val fontSize = prefs.getInt(RootConstants.KEY_HOOK_TEXT_SIZE, RootConstants.DEFAULT_HOOK_TEXT_SIZE)
        val baseTf = FontHelper.loadBaseTypeface(prefs)
        val narrowTf = FontHelper.loadNarrowTypeface(prefs)

        val textSizeRatio = prefs.getFloat(RootConstants.KEY_HOOK_TEXT_SIZE_RATIO, RootConstants.DEFAULT_HOOK_TEXT_SIZE_RATIO)
        val primarySizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat(), res.displayMetrics)

        val isMetadataDualLine = (mode == 5 || mode == 6)
        // Style 层永远允许 secondary 显示；翻译开关通过 view.displayTranslation/displayRoma
        // 控制 assembler 选什么内容，无内容时 assembler 返回 alwaysShow=false → secondary GONE
        val showSecondary = isMetadataDualLine || mode == 7

        val isLyricMode = mode == 7
        val isMarqueeEnabled = if (isLyricMode) {
            prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_MODE, RootConstants.DEFAULT_HOOK_MARQUEE_MODE)
        } else {
            prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_METADATA_MODE, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_MODE)
        }
        val marqueeSpeed = if (isLyricMode) {
            prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_SPEED, RootConstants.DEFAULT_HOOK_MARQUEE_SPEED)
        } else {
            prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_METADATA_SPEED, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_SPEED)
        }
        val marqueeDelay = if (isLyricMode) {
            prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_DELAY, RootConstants.DEFAULT_HOOK_MARQUEE_DELAY)
        } else {
            prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_METADATA_DELAY, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_DELAY)
        }
        val marqueeLoopDelay = if (isLyricMode) {
            prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_LOOP_DELAY, RootConstants.DEFAULT_HOOK_MARQUEE_LOOP_DELAY)
        } else {
            prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_METADATA_LOOP_DELAY, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_LOOP_DELAY)
        }
        val infinite = if (isLyricMode) {
            prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_INFINITE, RootConstants.DEFAULT_HOOK_MARQUEE_INFINITE)
        } else {
            prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_METADATA_INFINITE, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_INFINITE)
        }
        val stopAtEnd = if (isLyricMode) {
            prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_STOP_END, RootConstants.DEFAULT_HOOK_MARQUEE_STOP_END)
        } else {
            true
        }

        // Determine text colors: use cover colors if enabled, otherwise white
        // （颜色 key 按歌词位置分组：摘要态沿用旧 key，其他位置传各自的 FontColorKeys）
        val useCustomColor = IslandRuntimePreferenceReader.getBoolean(
            prefs,
            fontColorKeys.customEnabled,
            RootConstants.DEFAULT_HOOK_CUSTOM_TEXT_COLOR_ENABLED
        )
        val customTextColor = IslandRuntimePreferenceReader.getInt(
            prefs,
            fontColorKeys.customColor,
            RootConstants.DEFAULT_HOOK_CUSTOM_TEXT_COLOR
        )
        val useMonetColor = IslandRuntimePreferenceReader.getBoolean(
            prefs,
            fontColorKeys.monet,
            RootConstants.DEFAULT_HOOK_MONET_TEXT_COLOR
        )
        val useCoverColor = IslandRuntimePreferenceReader.getBoolean(
            prefs,
            fontColorKeys.coverColor,
            RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_COLOR
        )
        val useCoverGradient = IslandRuntimePreferenceReader.getBoolean(
            prefs,
            fontColorKeys.coverGradient,
            RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_GRADIENT
        )
        val gradientCoverBackgroundActive =
            IslandRuntimePreferenceReader.getBoolean(
                prefs,
                RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM,
                RootConstants.DEFAULT_HOOK_ISLAND_LEFT_ALBUM,
            ) &&
                IslandRuntimePreferenceReader.getInt(
                    prefs,
                    RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE,
                    RootConstants.DEFAULT_HOOK_ISLAND_ALBUM_COVER_STYLE,
                ).let { style ->
                    style == RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT ||
                        style == RootConstants.ISLAND_ALBUM_COVER_STYLE_LINEAR_GRADIENT
                }

        val primaryColors: IntArray
        val bgColors: IntArray
        val hlColors: IntArray
        val resolvedPalette: CoverColorHelper.ResolvedPalette?
        val contrastAdjustment: CoverGradientTextContrastOptimizer.Adjustment?
        val fallbackReason: FallbackReason?

        if (useCustomColor) {
            val customPalette = customTextColorPalette(customTextColor)
            resolvedPalette = null
            contrastAdjustment = null
            primaryColors = customPalette.primary
            bgColors = customPalette.background
            hlColors = customPalette.highlight
            fallbackReason = null
        } else if (useMonetColor) {
            val monetPalette = monetTextColorPalette(resolveMonetTextColor(res))
            resolvedPalette = null
            contrastAdjustment = null
            primaryColors = monetPalette.primary
            bgColors = monetPalette.background
            hlColors = monetPalette.highlight
            fallbackReason = null
        } else if (useCoverColor) {
            resolvedPalette = CoverColorHelper.resolveTextColors(
                bitmap = albumBitmap,
                useGradient = useCoverGradient,
                songKey = mediaColorKey
            )
            if (resolvedPalette != null) {
                val extractedTextColors = if (useCoverGradient) {
                    CoverTextGradientPaletteOptimizer.optimize(resolvedPalette.colors.second)
                } else {
                    resolvedPalette.colors.second
                }
                contrastAdjustment = if (CoverGradientTextContrastOptimizer.shouldOptimize(
                        useCustomColor = useCustomColor,
                        useMonetColor = useMonetColor,
                        useCoverColor = useCoverColor,
                        gradientCoverBackgroundActive = gradientCoverBackgroundActive,
                        hasArtwork = albumBitmap != null,
                    ) && albumBitmap != null
                ) {
                    val backgroundAnchors =
                        CoverGradientTextContrastOptimizer.sampleBackgroundAnchors(albumBitmap)
                    CoverGradientTextContrastOptimizer.optimize(
                        textColors = extractedTextColors,
                        backgroundAnchors = backgroundAnchors,
                    )
                } else {
                    null
                }
                val darkColors = contrastAdjustment?.colors ?: extractedTextColors
                val translucentDarkColors = darkColors.map { Color.argb(191, Color.red(it), Color.green(it), Color.blue(it)) }.toIntArray()
                primaryColors = darkColors   // 无逐字/标题 -> 封面颜色
                bgColors = translucentDarkColors // 未唱到 -> 封面颜色(75%透明度)
                hlColors = darkColors        // 已唱到 -> 封面颜色
                fallbackReason = null
            } else {
                contrastAdjustment = null
                primaryColors = intArrayOf(Color.WHITE)
                bgColors = intArrayOf(Color.argb(128, 255, 255, 255))
                hlColors = intArrayOf(Color.WHITE)
                fallbackReason = FallbackReason.NO_ARTWORK_OR_CACHE
            }
        } else {
            resolvedPalette = null
            contrastAdjustment = null
            primaryColors = intArrayOf(Color.WHITE)
            bgColors = intArrayOf(Color.argb(128, 255, 255, 255))
            hlColors = intArrayOf(Color.WHITE)
            fallbackReason = FallbackReason.SETTING_DISABLED
        }

        // 封面铺满岛背景（渐变封面 / 线性渐变）时，背景取自封面、颜色不可预知：即使字体颜色选项为
        // 「封面色」，也可能因与封面同色而看不清（实测粉字压粉底）。这里对**最终**文字色做一次
        // 「可读性救援」，保证任意字体颜色选项（封面色/封面渐变色/莫奈/自定义/默认）都尽量清晰可读。
        val readabilityAnchors = if (gradientCoverBackgroundActive && albumBitmap != null) {
            CoverGradientTextContrastOptimizer.sampleBackgroundAnchors(albumBitmap)
        } else {
            null
        }
        val effectivePrimaryColors = readabilityAnchors?.let {
            CoverGradientTextContrastOptimizer.ensureReadable(primaryColors, it)
        } ?: primaryColors
        val effectiveHighlightColors = readabilityAnchors?.let {
            CoverGradientTextContrastOptimizer.ensureReadable(hlColors, it)
        } ?: hlColors
        val effectiveBgColors = if (readabilityAnchors != null) {
            // 未唱到部分沿用主色的 75% 透明度，保持与主句的层次。
            effectivePrimaryColors.map { color ->
                Color.argb(191, Color.red(color), Color.green(color), Color.blue(color))
            }.toIntArray()
        } else {
            bgColors
        }

        val style = LyricViewStyle(
            primary = TextLook(
                color = effectivePrimaryColors,
                size = primarySizePx,
                typeface = baseTf,
                narrowTypeface = narrowTf,
                relativeProgress = prefs.getBoolean(RootConstants.KEY_HOOK_SYLLABLE_RELATIVE, RootConstants.DEFAULT_HOOK_SYLLABLE_RELATIVE),
                relativeHighlight = prefs.getBoolean(RootConstants.KEY_HOOK_SYLLABLE_HIGHLIGHT, RootConstants.DEFAULT_HOOK_SYLLABLE_HIGHLIGHT),
            ),
            secondary = TextLook(
                color = if (showSecondary) effectivePrimaryColors else intArrayOf(Color.TRANSPARENT),
                size = if (showSecondary) primarySizePx * textSizeRatio else 0f,
                typeface = baseTf,
                narrowTypeface = narrowTf,
            ),
            highlight = Highlight(
                background = effectiveBgColors,
                foreground = effectiveHighlightColors,
            ),
            marquee = Marquee(
                speed = if (isMarqueeEnabled) marqueeSpeed.toFloat() else 0f,
                initialDelay = marqueeDelay,
                loopDelay = marqueeLoopDelay,
                repeatCount = if (!isMarqueeEnabled) 0 else if (infinite) -1 else 1,
                stopAtEnd = stopAtEnd,
            ),
            gradient = prefs.getBoolean(RootConstants.KEY_HOOK_GRADIENT_PROGRESS, RootConstants.DEFAULT_HOOK_GRADIENT_PROGRESS),
            fadingEdge = prefs.getInt(RootConstants.KEY_HOOK_FADING_EDGE_LENGTH, RootConstants.DEFAULT_HOOK_FADING_EDGE_LENGTH),
            wordMotion = WordMotion(
                enabled = prefs.getBoolean(RootConstants.KEY_HOOK_WORD_MOTION_ENABLED, RootConstants.DEFAULT_HOOK_WORD_MOTION_ENABLED),
                cjkLiftFactor = prefs.getFloat(RootConstants.KEY_HOOK_WORD_MOTION_CJK_LIFT, RootConstants.DEFAULT_HOOK_WORD_MOTION_CJK_LIFT),
                cjkWaveFactor = prefs.getFloat(RootConstants.KEY_HOOK_WORD_MOTION_CJK_WAVE, RootConstants.DEFAULT_HOOK_WORD_MOTION_CJK_WAVE),
                latinLiftFactor = prefs.getFloat(RootConstants.KEY_HOOK_WORD_MOTION_LATIN_LIFT, RootConstants.DEFAULT_HOOK_WORD_MOTION_LATIN_LIFT),
                latinWaveFactor = prefs.getFloat(RootConstants.KEY_HOOK_WORD_MOTION_LATIN_WAVE, RootConstants.DEFAULT_HOOK_WORD_MOTION_LATIN_WAVE),
            ),
            placeholder = TitleSlot.NONE,
            centerIfPossible = false,
            alignRight = false,
        )
        return StyleBuildResult(
            style = style,
            colorResolution = ColorResolution(
                useMonetColor = !useCustomColor && useMonetColor,
                useCoverColor = !useCustomColor && !useMonetColor && useCoverColor,
                useCoverGradient = !useCustomColor && !useMonetColor && useCoverColor && useCoverGradient,
                paletteSource = resolvedPalette?.source,
                requestedKey = resolvedPalette?.requestedKey,
                resolvedKey = resolvedPalette?.resolvedKey,
                artworkSignature = resolvedPalette?.artworkSignature,
                contrastAdjustment = contrastAdjustment,
                fallbackReason = fallbackReason,
                primaryColors = effectivePrimaryColors,
                backgroundColors = effectiveBgColors,
                highlightColors = effectiveHighlightColors
            )
        )
    }

    private fun resolveMonetTextColor(res: Resources): Int = runCatching {
        res.getColor(android.R.color.system_accent1_200, null)
    }.getOrDefault(Color.WHITE)

}
