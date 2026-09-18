package com.genius.hyperlyrics.root.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.createBitmap
import com.genius.hyperlyrics.root.utils.HookLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 摘要态超级岛「线性渐变」背景（音频封面样式 = 线性渐变）。
 *
 * 渲染方式对齐焦点通知卡片的线性渐变观感：**专辑封面铺满整条胶囊**（centerCrop），
 * 再叠一层「左重-中轻-右中」的暗色线性渐变压边，最后按封面平均色做一次低透明度提色。
 * 这样封面本身清晰可辨，同时保证歌词文字可读、配色与封面同源。
 *
 * 注意：不复用通知卡片的线性渐变渲染器——那套几何按「高卡片」设计（封面只占 1.25×高度
 * 的一小条并被底色覆盖），放到 4.5:1 的胶囊上会只剩底色渐变，看不出封面。
 */
internal object IslandLinearGradientBackgroundApplier {
    private const val TAG = "IslandLinearGradientBg"

    /** 是否叠加暗色压边（false = 纯封面直出，不做任何处理）。 */
    private const val APPLY_SCRIM = false

    /** 是否叠加封面平均色提色（APPLY_SCRIM 为 false 时无效）。 */
    private const val APPLY_TINT = false

    /** 暗色压边各档透明度（左→右），中间最轻以便露出封面。 */
    private val SCRIM_ALPHAS = intArrayOf(184, 96, 36, 140)
    private val SCRIM_STOPS = floatArrayOf(0f, 0.35f, 0.62f, 1f)

    /** 封面平均色提色的透明度（越低越保留封面原貌）。 */
    private const val TINT_ALPHA = 56

    private val states = Collections.synchronizedMap(WeakHashMap<View, State>())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HyperLyrics-IslandLinearGradient").apply { isDaemon = true }
    }

    private class State(
        val view: View,
        val original: Drawable?,
    ) {
        var fingerprint: Long = 0L
        var width: Int = 0
        var height: Int = 0

        /** 本模块写入的渐变背景，用于判断背景是否被其它模块替换后写回。 */
        var drawable: BitmapDrawable? = null

        /** 背景重 assert 观察者（其它模块改写背景后把本模块背景写回）。 */
        var reassertListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
    }

    private val loggedReasons = Collections.synchronizedSet(mutableSetOf<String>())

    /** 去重日志：release 版也能从 logcat 判断本模块样式是否被应用以及未应用的原因。 */
    private fun logOnce(reason: String) {
        val isNew = synchronized(loggedReasons) {
            if (loggedReasons.size > 32) loggedReasons.clear()
            loggedReasons.add(reason)
        }
        if (isNew) HookLogger.i(TAG, "线性渐变背景未应用: $reason")
    }

    /**
     * 应用线性渐变背景。
     * @param owner 岛的封面 ImageView（用于定位所属岛的背景视图）
     * @param artwork 当前封面 Drawable；为空时回退到播放器应用图标
     * @param artworkBitmap 本模块缓存的原始封面位图（优先使用，避免取到过渡/组合 Drawable）
     */
    fun apply(
        owner: View,
        artwork: Drawable?,
        packageName: String?,
        artworkBitmap: Bitmap? = null,
    ) {
        val backgroundView = resolveIslandBackgroundView(owner) ?: run {
            logOnce("未找到岛背景视图 owner=${owner.javaClass.simpleName}")
            return
        }
        val width = backgroundView.width.takeIf { it > 0 } ?: backgroundView.measuredWidth
        val height = backgroundView.height.takeIf { it > 0 } ?: backgroundView.measuredHeight
        if (width <= 0 || height <= 0) {
            logOnce("岛背景尺寸无效: ${width}x$height")
            return
        }
        if (!backgroundView.isAttachedToWindow) {
            logOnce("岛背景视图未 attach")
            return
        }

        val state = states.getOrPut(backgroundView) {
            State(backgroundView, backgroundView.background)
        }
        val artworkFingerprint = artworkBitmap
            ?.takeIf { !it.isRecycled }
            ?.let { System.identityHashCode(it).toLong() }
            ?: artworkFingerprint(artwork)
        if (state.fingerprint == artworkFingerprint &&
            state.width == width &&
            state.height == height
        ) {
            return
        }
        state.fingerprint = artworkFingerprint
        state.width = width
        state.height = height

        val context = backgroundView.context
        executor.execute {
            val rendered = runCatching {
                renderIslandBackground(context, artwork, artworkBitmap, packageName, width, height)
            }.onFailure { error ->
                HookLogger.e(TAG, "渲染摘要态线性渐变背景失败", error)
            }.getOrNull() ?: return@execute

            backgroundView.post {
                if (states[backgroundView] !== state) {
                    rendered.recycle()
                    return@post
                }
                val drawable = BitmapDrawable(backgroundView.resources, rendered)
                state.drawable = drawable
                backgroundView.background = drawable
                attachReassert(state)
                HookLogger.i(
                    TAG,
                    "摘要态线性渐变背景已应用: size=${rendered.width}x${rendered.height}, " +
                        "package=$packageName",
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
        detachReassert(state)
        view.post {
            view.background = state.original
        }
    }

    /**
     * 前置绘制校验：其它模块（或系统）把背景改写后，把本模块背景写回。
     * 与绘制兜底互补——draw 兜底处理「绕过所有 setter 直接换 drawable」的情况，
     * 这里处理「背景字段被直接改写」且当前类没有可挂 draw 的情况。
     */
    private fun attachReassert(state: State) {
        if (state.reassertListener != null) return
        val view = state.view
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            if (synchronized(states) { states[view] } !== state) {
                detachReassert(state)
                return@OnPreDrawListener true
            }
            val drawable = state.drawable
            if (drawable != null && view.background !== drawable) {
                view.background = drawable
            }
            true
        }
        state.reassertListener = listener
        runCatching {
            view.viewTreeObserver.takeIf { it.isAlive }?.addOnPreDrawListener(listener)
        }
    }

    private fun detachReassert(state: State) {
        val listener = state.reassertListener ?: return
        state.reassertListener = null
        runCatching {
            state.view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
    }

    /**
     * 渲染岛背景：封面 centerCrop 铺满 → 暗色线性渐变压边 → 封面平均色低透明度提色。
     */
    private fun renderIslandBackground(
        context: Context,
        artwork: Drawable?,
        artworkBitmap: Bitmap?,
        packageName: String?,
        width: Int,
        height: Int,
    ): Bitmap? {
        val source = artworkBitmap?.takeIf { !it.isRecycled }
            ?: resolveArtworkBitmap(context, artwork, packageName)
            ?: return null
        val result = createBitmap(width, height)
        val canvas = Canvas(result)
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(
            source,
            centerCropRect(source, width, height),
            Rect(0, 0, width, height),
            bitmapPaint,
        )

        if (APPLY_SCRIM) {
            val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    width.toFloat(),
                    0f,
                    SCRIM_ALPHAS.map { alpha -> Color.argb(alpha, 0, 0, 0) }.toIntArray(),
                    SCRIM_STOPS,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        }

        if (APPLY_SCRIM && APPLY_TINT) {
            // 与封面同色调的低透明度提色：让暗部与封面配色融合而非纯黑。
            val tint = averageColor(source)
            runCatching {
                val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color =
                        Color.argb(TINT_ALPHA, Color.red(tint), Color.green(tint), Color.blue(tint))
                    blendMode = BlendMode.SOFT_LIGHT
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
            }
        }
        return result
    }

    private fun resolveArtworkBitmap(
        context: Context,
        artwork: Drawable?,
        packageName: String?,
    ): Bitmap? {
        val drawable = artwork ?: packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
            runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        } ?: return null
        (drawable as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }?.let { return it }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 512
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
        return runCatching {
            val bitmap = createBitmap(width, height)
            val previousBounds = Rect(drawable.bounds)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            drawable.bounds = previousBounds
            bitmap
        }.getOrNull()
    }

    /** 以目标宽高比做 centerCrop，返回源位图上的裁剪矩形。 */
    private fun centerCropRect(source: Bitmap, width: Int, height: Int): Rect {
        if (source.width <= 0 || source.height <= 0) return Rect(0, 0, 1, 1)
        val targetAspect = width.toFloat() / height.toFloat()
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        val srcWidth: Int
        val srcHeight: Int
        if (sourceAspect > targetAspect) {
            srcHeight = source.height
            srcWidth = (srcHeight * targetAspect).toInt().coerceAtLeast(1)
        } else {
            srcWidth = source.width
            srcHeight = (srcWidth / targetAspect).toInt().coerceAtLeast(1)
        }
        val left = (source.width - srcWidth) / 2
        val top = (source.height - srcHeight) / 2
        return Rect(left, top, left + srcWidth, top + srcHeight)
    }

    /** 封面平均色：稀疏采样，避免整图遍历。 */
    private fun averageColor(source: Bitmap): Int = runCatching {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        val stepX = (source.width / 16).coerceAtLeast(1)
        val stepY = (source.height / 16).coerceAtLeast(1)
        var x = 0
        while (x < source.width) {
            var y = 0
            while (y < source.height) {
                val color = source.getPixel(x, y)
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count++
                y += stepY
            }
            x += stepX
        }
        if (count == 0) {
            Color.GRAY
        } else {
            Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        }
    }.getOrDefault(Color.GRAY)

    /** 封面指纹：优先按位图实例，避免同一 Drawable 对象换歌时漏刷新。 */
    private fun artworkFingerprint(artwork: Drawable?): Long {
        val bitmap = (artwork as? BitmapDrawable)?.bitmap
        return if (bitmap != null) {
            System.identityHashCode(bitmap).toLong()
        } else {
            System.identityHashCode(artwork).toLong()
        }
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
}
