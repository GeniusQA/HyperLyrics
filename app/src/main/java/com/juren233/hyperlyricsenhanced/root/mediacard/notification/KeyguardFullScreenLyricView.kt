package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
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
 * 当前行组（粗体主歌词 + 翻译）从时钟下方顶部开始向下排列，
 * 其余组依次向下滑动并按距离渐隐，上下边缘渐隐，随时间轴行切换平滑滚动。
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
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(18f * density, 0f, 0f, Color.argb(110, 255, 255, 255))
    }
    private val inactivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val activeTranslationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val inactiveTranslationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val groupGap = 24f * density
    private val mainTranslationGap = 5f * density

    private val layoutCache = HashMap<Int, Pair<StaticLayout, StaticLayout?>>()

    init {
        applyDefaultStyle()
    }

    /** 默认字号（未读取到配置时兜底）。 */
    private fun applyDefaultStyle() {
        setStyle(26f, 14f)
    }

    /** 应用「锁屏歌词配置」中的字号设置（主句/翻译字号实时生效）。 */
    fun setStyle(mainTextSizeSp: Float, translationTextSizeSp: Float) {
        val mainPx = mainTextSizeSp * density
        val transPx = translationTextSizeSp * density
        activePaint.textSize = mainPx
        // 非当前行略缩小形成层次
        inactivePaint.textSize = mainPx * 0.85f
        activeTranslationPaint.textSize = transPx
        inactiveTranslationPaint.textSize = transPx * 0.9f
        layoutCache.clear()
        recomputeMetrics()
        invalidate()
    }

    /**
     * 应用字体颜色（字体颜色设置）：单色直接着色；渐变在布局完成后
     * 以当前视图宽度构建水平 LinearGradient 套到主句画笔上。
     */
    fun setFontColor(mainColors: IntArray, translationColor: Int) {
        if (mainColors.isEmpty()) return
        if (mainColors.size > 1) {
            post {
                if (width <= 0) return@post
                val shader = LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    mainColors, null, Shader.TileMode.CLAMP
                )
                activePaint.shader = shader
                inactivePaint.shader = shader
                layoutCache.clear()
                invalidate()
            }
            activeTranslationPaint.shader = null
            inactiveTranslationPaint.shader = null
            activeTranslationPaint.color = translationColor
            inactiveTranslationPaint.color = translationColor
        } else {
            val color = mainColors.first()
            activePaint.shader = null
            inactivePaint.shader = null
            activePaint.color = color
            inactivePaint.color = color
            activeTranslationPaint.shader = null
            inactiveTranslationPaint.shader = null
            activeTranslationPaint.color = translationColor
            inactiveTranslationPaint.color = translationColor
        }
        layoutCache.clear()
        invalidate()
    }

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
            duration = 340L
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
        val width = (width - contentSidePadding() * 2).coerceAtLeast(1f).toInt()
        val text = line?.text.orEmpty()
        val main = StaticLayout.Builder
            .obtain(text, 0, text.length, if (active) activePaint else inactivePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(2f * density, 1f)
            .setIncludePad(false)
            .build()
        val translationText = line?.translation?.takeIf { it.isNotBlank() }
        val translation = translationText?.let {
            StaticLayout.Builder
                .obtain(it, 0, it.length, if (active) activeTranslationPaint else inactiveTranslationPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
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
        val width = (width - contentSidePadding() * 2).coerceAtLeast(1f).toInt()
        val text = line.text.orEmpty()
        val main = StaticLayout.Builder
            .obtain(text, 0, text.length, activePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(2f * density, 1f)
            .setIncludePad(false)
            .build()
        var height = main.height.toFloat()
        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
            val trans = StaticLayout.Builder
                .obtain(translation, 0, translation.length, activeTranslationPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            height += mainTranslationGap + trans.height
        }
        return height + groupGap
    }

    /** 当前行组顶部锚定在绘制区间顶部（时钟正下方）。 */
    private fun anchorOffset(index: Int): Float {
        if (index < 0 || index >= offsets.size) return 0f
        val anchorY = drawTop + 8f * density
        return anchorY - offsets[index]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty() || currentIndex < 0 || drawBottom <= drawTop) return
        // 全屏暗色背景：模拟 ColorOS 锁屏歌词的整屏压暗效果——
        // 顶部稍轻，其余整屏统一压暗（含媒体卡片下方区域），避免出现明暗分界
        val heightF = height.toFloat()
        val bgPaint = Paint()
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, heightF,
            intArrayOf(
                Color.argb(90, 4, 4, 6),
                Color.argb(170, 4, 4, 6),
                Color.argb(170, 4, 4, 6),
            ),
            floatArrayOf(0f, 0.12f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), heightF, bgPaint)
        val shift = if (previousIndex in offsets.indices && scrollFraction < 1f) {
            val target = anchorOffset(currentIndex)
            val from = anchorOffset(previousIndex)
            from + (target - from) * scrollFraction
        } else {
            anchorOffset(currentIndex)
        }
        val sidePad = contentSidePadding()
        for (index in lines.indices) {
            val groupTop = offsets[index] + shift
            val groupBottom = groupTop + heights[index]
            if (groupBottom < drawTop || groupTop > drawBottom) continue
            val active = index == currentIndex
            val distance = abs(index - currentIndex)
            val (mainLayout, translationLayout) = lineLayouts(index, active)
            canvas.save()
            canvas.translate(sidePad, groupTop)
            drawStaticLayout(canvas, mainLayout, 0f, groupAlpha(distance))
            translationLayout?.let { layout ->
                drawStaticLayout(
                    canvas,
                    layout,
                    groupTop + mainLayout.height + mainTranslationGap,
                    translationAlpha(distance),
                )
            }
            canvas.restore()
        }
    }

    /** 亮度梯度：当前行最亮，随距离快速衰减（对齐 ColorOS 效果）。 */
    private fun groupAlpha(distance: Int): Int = when (distance) {
        0 -> 255
        1 -> 200
        2 -> 140
        3 -> 100
        else -> 80
    }

    private fun translationAlpha(distance: Int): Int = when (distance) {
        0 -> 185
        1 -> 120
        2 -> 85
        else -> 60
    }

    private fun contentSidePadding(): Float = 24f * density

    private fun drawStaticLayout(canvas: Canvas, layout: StaticLayout, top: Float, alpha: Int) {
        canvas.save()
        canvas.translate(0f, top)
        val paint = layout.paint as? TextPaint
        val previousAlpha = paint?.alpha
        paint?.alpha = alpha
        // 当前组主歌词保留辉光，非当前组关闭避免暗行发灰
        if (alpha < 240) {
            paint?.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            layout.draw(canvas)
            paint?.setShadowLayer(18f * density, 0f, 0f, Color.argb(110, 255, 255, 255))
        } else {
            layout.draw(canvas)
        }
        previousAlpha?.let { paint?.alpha = it }
        canvas.restore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeMetrics()
    }

    companion object {
        private const val MAX_CACHE = 32
    }
}
