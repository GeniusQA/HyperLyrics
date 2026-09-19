package com.genius.hyperlyrics.root.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.createBitmap
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.root.mediacard.notification.background.MediaBackgroundRendererPool
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

    /**
     * 封面露出区域占岛宽的比例。
     *
     * 焦点通知卡片是 2:1 的高卡片，封面按 1.25×高度取样就占了大半张卡；超级岛是约 4.5:1 的
     * 胶囊，同样算法只会剩一条细边，因此按比例放大封面占比，其余逻辑（底色 + 三段渐变融合）保持一致。
     */
    private const val COVER_WIDTH_FRACTION = 0.55f

    /** 封面最小宽度：与卡片一致，不低于 1.25×高度，避免封面被压得太窄。 */
    private const val MIN_COVER_WIDTH_IN_HEIGHTS = 1.25f

    /** 卡片同款三段渐变：底色不透明 → 半透明 → 近乎透明（露出封面）。 */
    private val GRADIENT_ALPHAS = intArrayOf(255, 144, 24)
    private val GRADIENT_STOPS = floatArrayOf(0f, 0.45f, 1f)

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

        /** 本模块写入的封面背景，用于判断背景是否被其它模块替换后写回。 */
        var drawable: Drawable? = null

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
                val drawable = CapsuleCoverBackgroundDrawable(
                    bitmap = rendered,
                    cornerRadius = height / 2f,
                )
                state.drawable = drawable
                backgroundView.background = drawable
                attachReassert(state)
                HookLogger.i(
                    TAG,
                    "摘要态线性渐变背景已应用: target=${backgroundView.javaClass.simpleName}, " +
                        "size=${rendered.width}x${rendered.height}, package=$packageName",
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
     * 渲染岛背景：完全对齐焦点通知卡片「线性渐变」的设计逻辑——
     * 整块先铺封面取色底色，封面在右侧 centerCrop 露出，
     * 再用「底色不透明 → 半透明 → 近乎透明」的横向渐变把封面边缘融合进底色。
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
        val baseColor = resolveBaseColor(context, artwork, source, packageName, width, height)
        val result = createBitmap(width, height)
        val canvas = Canvas(result)
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(baseColor)

        val coverWidth = (width * COVER_WIDTH_FRACTION).toInt()
            .coerceIn((height * MIN_COVER_WIDTH_IN_HEIGHTS).toInt(), width)
            .coerceAtLeast(1)
        val coverLeft = width - coverWidth
        canvas.drawBitmap(
            source,
            centerCropRect(source, coverWidth, height),
            Rect(coverLeft, 0, width, height),
            bitmapPaint,
        )

        // 卡片同款渐变：从封面左缘起始，底色不透明 → 半透明 → 近乎透明，封面在右端自然露出。
        val opaque = Color.argb(
            GRADIENT_ALPHAS[0],
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor),
        )
        val shader = LinearGradient(
            coverLeft.toFloat(),
            0f,
            width.toFloat(),
            0f,
            intArrayOf(
                opaque,
                opaque.withAlpha(GRADIENT_ALPHAS[1]),
                opaque.withAlpha(GRADIENT_ALPHAS[2]),
            ),
            GRADIENT_STOPS,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(
            coverLeft.toFloat(),
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader },
        )
        return result
    }

    /** 整数 alpha 版本（Color 没有 withAlpha 扩展时使用）。 */
    private fun Int.withAlpha(alpha: Int): Int =
        (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /**
     * 底色取色：复用焦点通知卡片的调色逻辑（Monet/封面取色 + 对比度处理），
     * 取不到时退回封面平均色，保证任何封面下都有可读的底色。
     */
    private fun resolveBaseColor(
        context: Context,
        artwork: Drawable?,
        source: Bitmap,
        packageName: String?,
        width: Int,
        height: Int,
    ): Int {
        val fromRenderer = runCatching {
            val renderer = MediaBackgroundRendererPool.get(context.classLoader)
            val rendered = renderer.renderDrawable(
                context = context,
                artworkDrawable = artwork,
                packageName = packageName.orEmpty(),
                style = RootConstants.NOTIFICATION_MEDIA_BACKGROUND_STYLE_LINEAR_GRADIENT,
                blurAmount = 0,
                autoInvert = false,
                softCoverTone = RootConstants.MEDIA_SOFT_COVER_TONE_DARK,
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
            )
            rendered?.let { value ->
                value.bitmap.recycle()
                value.colors.backgroundStart
            }
        }.getOrNull()
        return fromRenderer ?: averageColor(source)
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

    /**
     * 定位承载「岛内背景」的视图。
     *
     * 优先系统真实命名的 `area_left`（大岛内容区，也是原生大岛封面的写入目标，尺寸即胶囊区域），
     * 避免写到整块岛容器上导致封面铺满更大范围而溢出。
     */
    private fun resolveIslandBackgroundView(owner: View): View? {
        val root = owner.rootView as? ViewGroup
        if (root != null) {
            IslandViewHelper.findViewByName(root, "area_left")?.let { return it }
            IslandViewHelper.findViewByName(root, "fake_area_left")?.let { return it }
        }
        var current: View? = owner
        while (current != null) {
            val className = current.javaClass.name
            // 与系统实际命名对齐：DynamicIslandBackgroundView（同时含 dynamicisland 与 background）。
            if (className.contains("dynamicisland", ignoreCase = true) &&
                className.contains("background", ignoreCase = true)
            ) {
                return current
            }
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

/**
 * 把预渲染好的封面背景按胶囊圆角裁剪绘制。
 *
 * 背景视图的 bounds 有时比可见胶囊略大（含系统预留空间），直接铺位图会溢出胶囊外，
 * 因此这里统一按圆角裁剪，保证只在胶囊内可见。
 */
private class CapsuleCoverBackgroundDrawable(
    private val bitmap: Bitmap,
    private val cornerRadius: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val target = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        target.set(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
        )
        clipPath.reset()
        clipPath.addRoundRect(target, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || bitmap.isRecycled) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(bitmap, null, target, paint)
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
