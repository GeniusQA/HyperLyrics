package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 全屏锁屏歌词渲染视图（ColorOS 风格）：
 * 时钟下方到媒体卡片上方之间的区域，多行滚动歌词——
 * 当前行高亮大字（含翻译），其余行暗淡，随时间轴行切换平滑滚动，上下边缘渐隐。
 */
internal class KeyguardFullScreenLyricView(context: Context) : View(context) {

    private var lines: List<IRichLyricLine> = emptyList()
    private var heights: FloatArray = FloatArray(0)
    private var offsets: FloatArray = FloatArray(0)
    private var currentIndex = -1
    private var previousIndex = -1
    private var scrollFraction = 1f
    private var animator: ValueAnimator? = null

    // 绘制区间（本视图坐标系）：时钟底部 ↔ 媒体卡片顶部
    private var drawTop = 0f
    private var drawBottom = 0f

    private val density = resources.displayMetrics.density
    private val activePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f * density
        color = Color.WHITE
    }
    private val inactivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f * density
        color = Color.WHITE
    }
    private val activeTranslationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * density
        color = Color.WHITE
    }
    private val inactiveTranslationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        color = Color.WHITE
    }
    private val lineGap = 20f * density
    private val mainTranslationGap = 6f * density
    private val fadePaint = Paint()

    private val layoutCache = HashMap<Int, Pair<StaticLayout, StaticLayout?>>()

    /** 更新歌词数据（已过滤标题行），重置滚动与缓存。 */
    fun setData(newLines: List<IRichLyricLine>) {
        val changed = newLines.size != lines.size ||
            newLines.indices.any { newLines[it] !== lines[it] }
        if (!changed) return
        lines = newLines
        layoutCache.clear()
        currentIndex = -1
        previousIndex = -1
        animator?.cancel()
        animator = null
        scrollFraction = 1f
        heights = FloatArray(lines.size)
        offsets = FloatArray(lines.size)
        recomputeMetrics()
        invalidate()
    }

    /** 设置绘制区间（本视图坐标系），变化时重算度量。 */
    fun setDrawBounds(top: Float, bottom: Float) {
        if (drawTop != top || drawBottom != bottom) {
            drawTop = top
            drawBottom = bottom
            layoutCache.clear()
            invalidate()
        }
    }

    /** 更新当前行索引，行变化时启动平滑滚动动画。 */
    fun setCurrentIndex(index: Int) {
        val clamped = index.coerceIn(-1, lines.lastIndex)
        if (clamped == currentIndex) return
        previousIndex = currentIndex
        currentIndex = clamped
        animator?.cancel()
        animator = null
        if (previousIndex < 0 || currentIndex < 0 || previousIndex == currentIndex) {
            scrollFraction = 1f
            invalidate()
            return
        }
        scrollFraction = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                scrollFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun release() {
        animator?.cancel()
        animator = null
        layoutCache.clear()
    }

    private fun lineLayouts(index: Int, active: Boolean): Pair<StaticLayout, StaticLayout?> {
        layoutCache[index]?.let { return it }
        val line = lines.getOrNull(index)
        val width = width.coerceAtLeast(1)
        val text = line?.text.orEmpty()
        val main = StaticLayout.Builder
            .obtain(text, 0, text.length, if (active) activePaint else inactivePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
        val translationText = line?.translation
            ?.takeIf { it.isNotBlank() && active }
        val translation = translationText?.let {
            StaticLayout.Builder
                .obtain(it, 0, it.length, activeTranslationPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
        }
        val pair = main to translation
        if (layoutCache.size > MAX_CACHE) layoutCache.clear()
        layoutCache[index] = pair
        return pair
    }

    private fun recomputeMetrics() {
        if (lines.isEmpty() || width <= 0) return
        var offset = 0f
        for (index in lines.indices) {
            offsets[index] = offset
            heights[index] = measureLineHeight(index)
            offset += heights[index]
        }
    }

    private fun measureLineHeight(index: Int): Float {
        val line = lines.getOrNull(index) ?: return 0f
        val width = width.coerceAtLeast(1)
        val text = line.text.orEmpty()
        val main = StaticLayout.Builder
            .obtain(text, 0, text.length, activePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        var height = main.height.toFloat()
        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
            val trans = StaticLayout.Builder
                .obtain(translation, 0, translation.length, inactiveTranslationPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            height += mainTranslationGap + trans.height
        }
        return height + lineGap
    }

    /** 当前行锚点：使当前行顶部落在绘制区间的锚点位置。 */
    private fun anchorOffset(index: Int): Float {
        if (index < 0 || index >= offsets.size) return 0f
        val anchorY = drawTop + (drawBottom - drawTop) * ANCHOR_RATIO
        return anchorY - offsets[index]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty() || currentIndex < 0 || drawBottom <= drawTop) return
        val shift = if (previousIndex in offsets.indices && scrollFraction < 1f) {
            val target = anchorOffset(currentIndex)
            val from = anchorOffset(previousIndex)
            from + (target - from) * scrollFraction
        } else {
            anchorOffset(currentIndex)
        }
        for (index in lines.indices) {
            val lineTop = offsets[index] + shift
            val lineBottom = lineTop + heights[index]
            if (lineBottom < drawTop || lineTop > drawBottom) continue
            val active = index == currentIndex
            val distance = abs(index - currentIndex)
            val (mainLayout, translationLayout) = lineLayouts(index, active)
            drawLayout(canvas, mainLayout, lineTop, if (active) 242 else max(70, 180 - distance * 55))
            translationLayout?.let { layout ->
                drawLayout(
                    canvas,
                    layout,
                    lineTop + mainLayout.height + mainTranslationGap,
                    if (active) 160 else max(48, 120 - distance * 36),
                )
            }
        }
        drawEdgeFade(canvas)
    }

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, top: Float, alpha: Int) {
        canvas.save()
        canvas.translate(0f, top)
        drawStaticLayout(canvas, layout, alpha)
        canvas.restore()
    }

    private fun drawStaticLayout(canvas: Canvas, layout: StaticLayout, alpha: Int) {
        val paint = layout.paint as? TextPaint
        val previousAlpha = paint?.alpha
        paint?.alpha = alpha
        layout.draw(canvas)
        previousAlpha?.let { paint?.alpha = it }
    }

    private fun drawEdgeFade(canvas: Canvas) {
        val fadeHeight = min(120f * density, (drawBottom - drawTop) / 4f)
        if (fadeHeight <= 0f) return
        fadePaint.shader = LinearGradient(
            0f, drawTop, 0f, drawTop + fadeHeight,
            intArrayOf(Color.argb(255, 0, 0, 0), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, drawTop, width.toFloat(), drawTop + fadeHeight, fadePaint)
        fadePaint.shader = LinearGradient(
            0f, drawBottom - fadeHeight, 0f, drawBottom,
            intArrayOf(Color.TRANSPARENT, Color.argb(255, 0, 0, 0)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, drawBottom - fadeHeight, width.toFloat(), drawBottom, fadePaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeMetrics()
    }

    companion object {
        private const val MAX_CACHE = 32
        private const val ANCHOR_RATIO = 0.30f
    }
}
