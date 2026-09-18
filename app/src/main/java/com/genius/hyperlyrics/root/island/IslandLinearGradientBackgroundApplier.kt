package com.genius.hyperlyrics.root.island

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.genius.hyperlyrics.root.utils.HookLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 摘要态超级岛「线性渐变」背景（音频封面样式 = 线性渐变）。
 *
 * 直接以当前专辑封面铺满整条岛背景：封面按 pill 尺寸等比放大后裁出中间偏上的一条
 * （保留封面主体、看得清是封面），再叠一层左右重、中间轻的横向线性渐变压暗，
 * 让岛内文字在任意封面上都能看清；最后按胶囊圆角裁剪，避免方角露出。
 */
internal object IslandLinearGradientBackgroundApplier {
    private const val TAG = "IslandLinearGradientBg"

    /** 裁切取景位置：0=封面顶部，1=底部；略偏上，优先取到封面主体/人像。 */
    private const val CROP_FOCUS_Y = 0.38f

    /** 压暗渐变：两端稍重（文字起止处）、中间最轻，尽量保留封面观感。 */
    private val SCRIM_COLORS = intArrayOf(
        Color.argb(158, 0, 0, 0),
        Color.argb(70, 0, 0, 0),
        Color.argb(56, 0, 0, 0),
        Color.argb(140, 0, 0, 0),
    )
    private val SCRIM_STOPS = floatArrayOf(0f, 0.30f, 0.65f, 1f)

    private val states = Collections.synchronizedMap(WeakHashMap<View, State>())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HyperLyrics-IslandCoverBackground").apply { isDaemon = true }
    }

    private class State(
        val view: View,
        val original: Drawable?,
    ) {
        var fingerprint: Long = 0L
        var width: Int = 0
        var height: Int = 0
    }

    /**
     * 应用封面背景。
     * @param owner 岛的封面 ImageView（用于定位所属岛的背景视图）
     * @param artwork 当前封面 Drawable
     */
    fun apply(owner: View, artwork: Drawable?, packageName: String?) {
        val owned = resolveCoverBitmap(artwork)
        val cover = owned?.bitmap ?: return
        val backgroundView = resolveIslandBackgroundView(owner)
        val width = backgroundView?.width?.takeIf { it > 0 } ?: backgroundView?.measuredWidth ?: 0
        val height = backgroundView?.height?.takeIf { it > 0 } ?: backgroundView?.measuredHeight ?: 0
        if (backgroundView == null || width <= 0 || height <= 0 ||
            !backgroundView.isAttachedToWindow
        ) {
            if (owned.created) cover.recycle()
            return
        }

        val state = states.getOrPut(backgroundView) {
            State(backgroundView, backgroundView.background)
        }
        // 指纹取封面位图实例；栅格化出来的临时位图则退化为按 Drawable 实例判定。
        val fingerprint = if (owned.created) {
            System.identityHashCode(artwork).toLong()
        } else {
            System.identityHashCode(cover).toLong()
        }
        if (state.fingerprint == fingerprint && state.width == width && state.height == height) {
            if (owned.created) cover.recycle()
            return
        }
        state.fingerprint = fingerprint
        state.width = width
        state.height = height

        executor.execute {
            val rendered = runCatching { renderCoverBackground(cover, width, height) }
                .onFailure { error -> HookLogger.e(TAG, "渲染封面背景失败", error) }
                .getOrNull()
            if (owned.created) cover.recycle()
            if (rendered == null) return@execute

            backgroundView.post {
                if (states[backgroundView] !== state) {
                    rendered.recycle()
                    return@post
                }
                backgroundView.background = RoundedCoverBackgroundDrawable(
                    bitmap = rendered,
                    cornerRadius = height / 2f,
                )
                HookLogger.d(
                    TAG,
                    "封面背景已应用: size=${rendered.width}x${rendered.height}, package=$packageName",
                )
            }
        }
    }

    /** 恢复指定岛的原生背景（样式切换/关闭时调用）。 */
    fun restoreFor(owner: View) {
        val backgroundView = resolveIslandBackgroundView(owner) ?: return
        restoreBackground(backgroundView)
    }

    /** 恢复全部已接管背景（清理/释放时调用）。 */
    fun restoreAll() {
        val views = synchronized(states) { states.keys.toList() }
        views.forEach(::restoreBackground)
    }

    private fun restoreBackground(view: View) {
        val state = states.remove(view) ?: return
        view.post { view.background = state.original }
    }

    private class CoverBitmap(val bitmap: Bitmap, val created: Boolean)

    /** 取封面位图：BitmapDrawable 直接复用其位图，其余情况栅格化一份（created=true 由调用方释放）。 */
    private fun resolveCoverBitmap(artwork: Drawable?): CoverBitmap? {
        val drawable = artwork ?: return null
        (drawable as? BitmapDrawable)?.bitmap?.let { return CoverBitmap(it, created = false) }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        val bitmap = runCatching {
            val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val originalBounds = drawable.copyBounds()
            try {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(target))
            } finally {
                drawable.bounds = originalBounds
            }
            target
        }.getOrNull() ?: return null
        return CoverBitmap(bitmap, created = true)
    }

    /** 封面等比铺满 + 横向渐变压暗，输出岛尺寸位图。 */
    private fun renderCoverBackground(source: Bitmap, width: Int, height: Int): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null
        val scale = max(width / source.width.toFloat(), height / source.height.toFloat())
        val scaledWidth = (source.width * scale).roundToInt().coerceAtLeast(width)
        val scaledHeight = (source.height * scale).roundToInt().coerceAtLeast(height)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val left = ((scaledWidth - width) / 2f).roundToInt().coerceIn(0, scaledWidth - width)
        val top = ((scaledHeight - height) * CROP_FOCUS_Y).roundToInt()
            .coerceIn(0, scaledHeight - height)
        canvas.drawBitmap(scaled, -left.toFloat(), -top.toFloat(), null)
        if (scaled !== source) scaled.recycle()
        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    width.toFloat(),
                    0f,
                    SCRIM_COLORS,
                    SCRIM_STOPS,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        return result
    }

    /** 沿父链定位超级岛背景视图（与原生命名一致的 DynamicIslandBackgroundView / getBackgroundView）。 */
    private fun resolveIslandBackgroundView(owner: View): View? {
        var current: View? = owner
        while (current != null) {
            if (current.javaClass.simpleName == "DynamicIslandBackgroundView") return current
            val background = runCatching {
                current.javaClass.methods.firstOrNull {
                    it.name == "getBackgroundView" && it.parameterTypes.isEmpty()
                }?.invoke(current) as? View
            }.getOrNull()
            if (background != null) return background
            current = current.parent as? View
        }
        return null
    }

    /** 按胶囊圆角裁剪的封面背景，避免方角从岛边缘露出。 */
    private class RoundedCoverBackgroundDrawable(
        private val bitmap: Bitmap,
        private val cornerRadius: Float,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clipPath = Path()
        private val clipRect = RectF()

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            super.onBoundsChange(bounds)
            clipRect.set(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())
            clipPath.reset()
            clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty || bitmap.isRecycled) return
            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(
                    bounds.left.toFloat(),
                    bounds.top.toFloat(),
                    bounds.right.toFloat(),
                    bounds.bottom.toFloat(),
                ),
                paint,
            )
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
