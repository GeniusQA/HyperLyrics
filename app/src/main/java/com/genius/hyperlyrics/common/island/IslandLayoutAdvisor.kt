/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.common.island

import kotlin.math.roundToInt

/**
 * 超级岛「左右内容长度 / 内容内边距」的真机自适应推荐值。
 *
 * 背景：这两组值原先只有写死的默认值（左 80dp、右 100dp、内边距 5,0,0,0），但合适的取值取决于
 * **真机参数**与**当前场景**：
 * - 真机参数：摘要胶囊稳态宽度、中间挖孔区域宽度、屏幕密度；
 * - 场景：左槽是否仍显示原生专辑 logo（「音频封面」开启且左侧=无内容）、右槽是否显示原生图标
 *   （「音频律动」）。
 *
 * 填小了歌词会被裁切/滚动，填大了「动态长度」会把胶囊撑得过长。这里按真机参数算出推荐值，
 * 供设置页作为默认值预填到输入框。
 *
 * 计算模型（摘要胶囊 = 左侧区域 + 中间挖孔区域 + 右侧区域，左右对称）：
 * 1. 摘要胶囊稳态宽度 ≈ 屏幕宽 × [CAPSULE_SCREEN_RATIO]
 *    （本机实测：495px / 1080px ≈ 0.458）
 * 2. 中间挖孔区域宽度取「物理摄像头挖孔宽度」与 [ISLAND_GAP_MIN_DP] 的较大者
 *    （本机实测反推：胶囊 469px 时 `area_left`=146px ⇒ 中间预留 177px ≈ 64dp）
 * 3. 每侧可用宽度 = (胶囊宽 − 挖孔宽) / 2；左槽若仍显示原生专辑 logo 再减去 logo 占位
 *    （本机实测：左模块 69px − 文本容器 8px ≈ 61px ≈ 22dp）
 * 4. 内边距取固定留白：贴边 [EDGE_PADDING_DP]，原生图标与文字之间 [LOGO_TEXT_GAP_DP]
 *
 * 换机型后如需校准：用超级岛探针打印 `area_left` / `area_right` / `area_cutout` 的实测宽度，
 * 再调整下面的常量即可（换算关系见上）。
 */
object IslandLayoutAdvisor {

    /** 摘要胶囊稳态宽度占屏幕宽的比例（本机实测反推 495/1080 ≈ 0.458）。 */
    private const val CAPSULE_SCREEN_RATIO = 0.46f

    /** 中间挖孔区域的最小预留宽度（dp）。本机实测反推 ≈ 64dp。 */
    private const val ISLAND_GAP_MIN_DP = 64f

    /** 原生专辑 logo 的占位宽度（dp）。本机实测 ≈ 22dp。 */
    private const val ALBUM_LOGO_WIDTH_DP = 22f

    /** 内容与胶囊边缘之间的留白（dp）。 */
    private const val EDGE_PADDING_DP = 5

    /** 原生图标与其后文字之间的留白（dp）。 */
    private const val LOGO_TEXT_GAP_DP = 6

    /** 推荐内容长度的下限（dp）。 */
    private const val MIN_CONTENT_WIDTH_DP = 8

    /** 推荐内容长度的上限（dp），与设置页输入范围一致。 */
    private const val MAX_CONTENT_WIDTH_DP = 500

    /**
     * 推荐结果（单位 dp）。
     *
     * @param leftContentWidthDp 左侧内容长度：左槽内容最多占多宽
     * @param rightContentWidthDp 右侧内容长度：右槽内容最多占多宽
     */
    data class Recommendation(
        val leftContentWidthDp: Int,
        val rightContentWidthDp: Int,
        val leftPaddingLeftDp: Int,
        val leftPaddingRightDp: Int,
        val rightPaddingLeftDp: Int,
        val rightPaddingRightDp: Int,
    )

    /**
     * 按真机参数与场景计算推荐值。
     *
     * @param screenWidthPx 屏幕宽度（px，取当前窗口宽度即可）
     * @param density 屏幕密度（`DisplayMetrics.density`）
     * @param cutoutWidthPx 物理摄像头挖孔宽度（px，取不到时传 0，会退化为 [ISLAND_GAP_MIN_DP]）
     * @param leftShowsAlbumLogo 左槽是否仍显示原生专辑 logo（「音频封面」开启且左侧=无内容）
     * @param rightShowsIcon 右槽是否显示原生图标（「音频律动」开启）
     */
    fun recommend(
        screenWidthPx: Int,
        density: Float,
        cutoutWidthPx: Int,
        leftShowsAlbumLogo: Boolean,
        rightShowsIcon: Boolean,
    ): Recommendation {
        val safeDensity = density.takeIf { it > 0f } ?: 1f
        val capsulePx = screenWidthPx.coerceAtLeast(0) * CAPSULE_SCREEN_RATIO
        val gapPx = maxOf(
            cutoutWidthPx.coerceAtLeast(0).toFloat(),
            ISLAND_GAP_MIN_DP * safeDensity,
        )
        val sidePx = ((capsulePx - gapPx).coerceAtLeast(0f)) / 2f
        val logoPx = if (leftShowsAlbumLogo) ALBUM_LOGO_WIDTH_DP * safeDensity else 0f
        return Recommendation(
            leftContentWidthDp = toDp((sidePx - logoPx).coerceAtLeast(0f), safeDensity),
            rightContentWidthDp = toDp(sidePx, safeDensity),
            leftPaddingLeftDp = EDGE_PADDING_DP,
            leftPaddingRightDp = if (leftShowsAlbumLogo) LOGO_TEXT_GAP_DP else 0,
            rightPaddingLeftDp = if (rightShowsIcon) LOGO_TEXT_GAP_DP else 0,
            rightPaddingRightDp = 0,
        )
    }

    private fun toDp(px: Float, density: Float): Int =
        (px / density).roundToInt().coerceIn(MIN_CONTENT_WIDTH_DP, MAX_CONTENT_WIDTH_DP)
}
