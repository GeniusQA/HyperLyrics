package com.genius.hyperlyrics.common.lyric

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.genius.hyperlyrics.lyric.model.interfaces.IRichLyricLine
import kotlin.math.roundToInt

/**
 * 间奏等待标记（3 个等亮度圆点）的 TextView Span 工厂。
 *
 * 超级岛摘要态走自绘 InterludeDotsRenderer；锁屏/AOD/通知中心/展开岛这些
 * 原生 TextView 路径没有 Canvas，统一用本工厂把占位文本渲染成与摘要态
 * 造型一致的 3 点大句号：● ● ●（字号 0.9 倍、三点等亮度，不做暗点区分）。
 */
object InterludeDotsSpanFactory {

    /** 主行占位文本（两个空格分隔 3 个圆点，Span 按点位着色/缩放）。 */
    const val PLACEHOLDER = "● ● ●"

    const val DOT_COUNT = 3

    /** 圆点相对正文色素放大倍数：● 字形本身约 0.5em，放大后直径约 0.45 倍字号，与摘要态一致。 */
    private const val DOT_RELATIVE_SIZE = 0.9f

    /** 当前行是否为间奏等待标记（由 LyriconDataBridge 生成，带 INSTRUMENTAL 元数据）。 */
    fun isInterludeLine(line: IRichLyricLine?): Boolean =
        line?.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) == true

    /**
     * 构建 3 点 Span。
     * @param baseColor 基准文字颜色（取 TextView 当前色，随字体颜色偏好自动跟随）
     * @param alphaScale 整体亮度系数（1.0 全亮；呼吸动画按帧传入 0.55~1.0）
     */
    fun build(baseColor: Int, alphaScale: Float = 1f): SpannableString {
        val alpha = (255 * alphaScale).roundToInt().coerceIn(0, 255)
        val color = (baseColor and 0x00FFFFFF) or (alpha shl 24)
        return SpannableString(PLACEHOLDER).apply {
            for (i in 0 until DOT_COUNT) {
                val pos = i * 2
                setSpan(RelativeSizeSpan(DOT_RELATIVE_SIZE), pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(color), pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
