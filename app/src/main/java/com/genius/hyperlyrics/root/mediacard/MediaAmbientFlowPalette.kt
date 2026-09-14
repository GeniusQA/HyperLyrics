package com.genius.hyperlyrics.root.mediacard

import android.graphics.Bitmap
import com.genius.hyperlyrics.common.color.ColorExtractor
import java.lang.reflect.Method

internal data class MediaAmbientFlowPalette(
    val mainColor: Int,
    val colors: IntArray
)

internal object MediaAmbientFlowPaletteExtractor {
    // 必须与超级岛字体颜色「封面色」基准（CoverColorHelper 非渐变路径）保持同一调用形态：
    // maxColors=1 的 onBlackBackground 首色。改大 maxColors 会改变 k-means 聚类数，
    // 导致流光与字体封面色对同一封面选出不同主色。
    fun extractCoverMainColor(bitmap: Bitmap): Int? =
        ColorExtractor.extractThemePalette(bitmap, 1).onBlackBackground.firstOrNull()
}

/**
 * 由主色派生环境流光所需的三个色阶（primary/12、primary/10、tertiary/12）。
 *
 * 锁屏媒体卡片与超级岛展开态此前各维护一份逐字相同的实现，现统一在此，
 * [paletteColorMethod] 为系统 MiPalette.getPaletteColor(Int, String, Int) 的反射句柄。
 */
internal fun buildMediaAmbientFlowPalette(
    mainColor: Int,
    paletteColorMethod: Method
): MediaAmbientFlowPalette {
    fun paletteColor(role: String, tone: Int): Int =
        paletteColorMethod.invoke(null, mainColor, role, tone) as Int
    val colors = intArrayOf(
        paletteColor("primary", 12),
        paletteColor("primary", 10),
        paletteColor("tertiary", 12)
    )
    return MediaAmbientFlowPalette(mainColor, colors)
}
