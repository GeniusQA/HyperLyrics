package com.genius.hyperlyrics.root.utils

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.genius.hyperlyrics.common.RootConstants

/**
 * 非摘要态歌词（息屏AOD/锁屏歌词/通知中心/自定义AOD/大岛/全屏歌词）的字体颜色应用器。
 *
 * 复用「超级岛摘要态歌词」的取色链路（LyricStyleHelper + CoverColorHelper）：
 * 默认模式保持跟随系统控件颜色不动，其余模式（莫奈取色/封面色/封面渐变色/自定义）
 * 统一取色覆盖；渐变模式对 TextView 使用水平 LinearGradient shader。
 */
object OverlayFontColorApplier {
    // 与 ui/page/hooksettings/lyrics/display/LyricDisplayPage 的模式常量保持一致
    private const val MODE_DEFAULT = 0
    private const val MODE_MONET = 1
    private const val MODE_COVER = 2
    private const val MODE_COVER_GRADIENT = 3
    private const val MODE_CUSTOM = 4

    /** 从偏好解析指定位置的颜色模式（优先级与设置页写入逻辑一致：自定义>莫奈>封面渐变>封面>默认）。 */
    fun resolveMode(prefs: SharedPreferences, keys: RootConstants.FontColorKeys): Int = when {
        prefs.getBoolean(keys.customEnabled, RootConstants.DEFAULT_HOOK_CUSTOM_TEXT_COLOR_ENABLED) ->
            MODE_CUSTOM
        prefs.getBoolean(keys.monet, RootConstants.DEFAULT_HOOK_MONET_TEXT_COLOR) -> MODE_MONET
        !prefs.getBoolean(keys.coverColor, RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_COLOR) ->
            MODE_DEFAULT
        prefs.getBoolean(keys.coverGradient, RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_GRADIENT) ->
            MODE_COVER_GRADIENT
        else -> MODE_COVER
    }

    /**
     * 应用字体颜色到目标 TextView 组。
     *
     * @param targets 主句组（当前行/重叠主句/伴唱等）
     * @param secondaryTargets 次级组（翻译/和声翻译/下一句等），取主色降低透明度
     * @param albumBitmap 封面 bitmap（封面色/渐变模式用；取不到时回退默认白色）
     * @param mediaColorKey 封面取色缓存键（CoverColorHelper.updateMediaSession 的返回值）
     */
    fun apply(
        prefs: SharedPreferences?,
        res: android.content.res.Resources,
        keys: RootConstants.FontColorKeys,
        targets: List<TextView>,
        secondaryTargets: List<TextView>,
        albumBitmap: Bitmap?,
        mediaColorKey: String?,
    ) {
        if (prefs == null) return
        val mode = resolveMode(prefs, keys)
        if (mode == MODE_DEFAULT) return
        val style = runCatching {
            LyricStyleHelper.buildStyleWithFontColorKeys(
                prefs = prefs,
                res = res,
                mode = mode,
                keys = keys,
                albumBitmap = albumBitmap,
                mediaColorKey = mediaColorKey,
            )
        }.getOrNull() ?: return
        val primaryColors = style.primary.color
            .takeIf { it.isNotEmpty() } ?: return
        val mainColor = primaryColors.first()
        val secondaryColor = applySecondaryAlpha(mainColor)

        if (primaryColors.size > 1) {
            // 封面渐变色：水平线性渐变，等待布局拿到实际宽度
            targets.forEach { view -> applyGradient(view, primaryColors) }
            secondaryTargets.forEach { view ->
                view.paint.shader = null
                view.setTextColor(secondaryColor)
            }
        } else {
            targets.forEach { view ->
                view.paint.shader = null
                view.setTextColor(mainColor)
            }
            secondaryTargets.forEach { view ->
                view.paint.shader = null
                view.setTextColor(secondaryColor)
            }
        }
    }

    /** 次级文字（翻译等）取主色降低透明度，保持与主句的层次。 */
    fun applySecondaryAlpha(mainColor: Int): Int {
        val alpha = (Color.alpha(mainColor).coerceAtLeast(64) * 0.75f).toInt().coerceAtMost(255)
        return (alpha shl 24) or (mainColor and 0x00FFFFFF)
    }

    /** 是否为默认模式（跟随系统控件颜色，无需覆盖）。 */
    fun isDefaultMode(mode: Int): Boolean = mode == MODE_DEFAULT

    private fun applyGradient(view: TextView, colors: IntArray) {
        view.post {
            if (view.width <= 0) return@post
            view.paint.shader = LinearGradient(
                0f, 0f, view.width.toFloat(), 0f,
                colors, null, Shader.TileMode.CLAMP
            )
            // shader 优先于 textColor，保留占位色便于渐变生效
            view.setTextColor(Color.WHITE)
            view.invalidate()
        }
    }

    /**
     * 从媒体卡片的封面视图提取 bitmap（封面色/渐变模式取色用，失败返回 null）。
     * 非 BitmapDrawable（如自定义/矢量 drawable）时按内在尺寸光栅化，上限 512px。
     */
    fun bitmapFromAlbumView(view: View?): Bitmap? = runCatching {
        val drawable = (view as? ImageView)?.drawable ?: return@runCatching null
        (drawable as? BitmapDrawable)?.bitmap ?: run {
            val w = drawable.intrinsicWidth.takeIf { it in 1..512 } ?: 256
            val h = drawable.intrinsicHeight.takeIf { it in 1..512 } ?: 256
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bitmap
        }
    }.getOrNull()
}
