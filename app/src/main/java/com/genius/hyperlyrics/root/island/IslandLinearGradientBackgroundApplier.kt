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
import com.genius.hyperlyrics.BuildConfig
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

    /** 视图树搜索上限，避免异常层级导致遍历失控。 */
    private const val MAX_SEARCH_NODES = 512

    /** 岛重建/过渡期间目标暂不可用时的最大重试次数（间隔 250ms 递增，覆盖过渡+布局耗时）。 */
    private const val MAX_APPLY_RETRIES = 24

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

        /** 胶囊真实绘制区域（视图本地坐标），为空表示按视图自身 bounds 绘制。 */
        var capsule: RectF? = null

        /** 本模块写入的封面背景，用于判断背景是否被其它模块替换后写回。 */
        var drawable: Drawable? = null

        /** 背景重 assert 观察者（其它模块改写背景后把本模块背景写回）。 */
        var reassertListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
    }

    private val loggedReasons = Collections.synchronizedSet(mutableSetOf<String>())

    /** 岛重建/过渡期间目标暂不可用时的延迟重试计数（按封面视图记录）。 */
    private val applyRetryCounts = Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** 已输出过层级探针的范围（避免重复刷屏）。 */
    private val probedScopes = Collections.synchronizedSet(mutableSetOf<String>())

    /** 封面视图 → 实际写入的岛分段视图，供样式切换时精确恢复。 */
    private val targetsByOwner = Collections.synchronizedMap(WeakHashMap<View, List<View>>())

    /** 去重日志：release 版也能从 logcat 判断本模块样式是否被应用以及未应用的原因。 */
    private fun logOnce(reason: String) {
        val isNew = synchronized(loggedReasons) {
            if (loggedReasons.size > 32) loggedReasons.clear()
            loggedReasons.add(reason)
        }
        if (isNew) HookLogger.i(TAG, "线性渐变背景未应用: $reason")
    }

    /**
     * 目标暂不可用（容器重建/过渡中未 attach）时的延迟重试：
     * 切歌会触发岛重建，此刻直接放弃会导致背景持续缺失，直到用户手动刷新。
     */
    private fun scheduleApplyRetry(
        owner: View,
        artwork: Drawable?,
        packageName: String?,
        artworkBitmap: Bitmap?,
        host: View?,
    ) {
        val attempts = applyRetryCounts[owner] ?: 0
        if (attempts >= MAX_APPLY_RETRIES) return
        applyRetryCounts[owner] = attempts + 1
        owner.postDelayed(
            {
                applyRetryCounts.remove(owner)
                apply(owner, artwork, packageName, artworkBitmap, host)
            },
            250L * (attempts + 1),
        )
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
        host: View? = null,
    ) {
        val scope = (host as? ViewGroup) ?: (owner.rootView as? ViewGroup) ?: run {
            // 切歌时岛会经历重建/过渡，此刻可能暂时找不到容器：延迟重试而不是放弃，
            // 否则过渡结束后没有任何触发点，背景会一直缺失（表现为黑胶囊）。
            scheduleApplyRetry(owner, artwork, packageName, artworkBitmap, host)
            return
        }
        // 折叠态胶囊由多个分段视图拼成（area_left / area_right 等），只写其中一个只会覆盖一段，
        // 因此这里把所有分段视图都作为绘制目标，每段只画「按胶囊坐标映射后的那一块」封面。
        val targets = resolvePillTargets(scope).ifEmpty { listOf(scope) }
            .filter { it.isAttachedToWindow }
        if (targets.isEmpty()) {
            scheduleApplyRetry(owner, artwork, packageName, artworkBitmap, host)
            return
        }
        // 摘要态可能是「左右两个独立胶囊」（媒体胶囊 + 歌词胶囊，中间有间隙）。此时必须以
        // 所有胶囊的并集作为封面映射基准，各胶囊只画自己那一段，视觉上才是一条连续封面；
        // 若按单个胶囊映射，每个胶囊都会被塞进一份完整封面，看起来像被拆成好几个。
        // 布局未完成时（探针实测过渡期各分段为 0x0、坐标在屏幕外），并集会被算成残缺的一块，
        // 且缓存命中后再也不重算，导致封面一直只铺一小块。此时不绘制，等布局完成再重试。
        if (targets.any { it.width <= 0 || it.height <= 0 }) {
            scheduleApplyRetry(owner, artwork, packageName, artworkBitmap, host)
            return
        }
        val capsuleWindow = unionWindowRect(targets)
            ?: resolveCapsuleWindowRect(scope, owner)
        // 保险：并集若接近整窗宽，说明误收了整窗宽视图（历史上会造成整条顶部溢出），直接放弃绘制。
        val rootWidth = scope.rootView?.width ?: 0
        if (rootWidth > 0 && capsuleWindow != null && capsuleWindow.width() > rootWidth * 0.9f) {
            logOnce("并集过宽疑似含整窗视图: ${capsuleWindow.toShortString()}")
            return
        }
        // 探针范围取整棵岛窗口视图树：真实胶囊（532x116）不一定在 scope（容器）之内。
        logIslandHierarchyProbe(scope.rootView ?: scope)
        val first = targets.first()
        val firstCapsule = capsuleWindow?.let { toLocalRect(it, first) }
        val width = firstCapsule?.width()?.toInt()?.takeIf { it > 0 }
            ?: first.width.takeIf { it > 0 } ?: first.measuredWidth
        val height = firstCapsule?.height()?.toInt()?.takeIf { it > 0 }
            ?: first.height.takeIf { it > 0 } ?: first.measuredHeight
        if (width <= 0 || height <= 0) {
            logOnce("岛尺寸无效: ${width}x$height")
            return
        }

        val artworkFingerprint = artworkBitmap
            ?.takeIf { !it.isRecycled }
            ?.let { System.identityHashCode(it).toLong() }
            ?: artworkFingerprint(artwork)
        val capsuleKey = capsuleWindow?.toShortString().orEmpty()
        val upToDate = targets.all { view ->
            val state = states[view] ?: return@all false
            state.fingerprint == artworkFingerprint &&
                state.width == width &&
                state.height == height &&
                state.capsule?.toShortString().orEmpty() == capsuleKey
        }
        if (upToDate) return

        val context = first.context
        executor.execute {
            val rendered = runCatching {
                renderIslandBackground(context, artwork, artworkBitmap, packageName, width, height)
            }.onFailure { error ->
                HookLogger.e(TAG, "渲染摘要态线性渐变背景失败", error)
            }.getOrNull() ?: return@execute

            first.post {
                var applied = 0
                targets.forEach { view ->
                    if (!view.isAttachedToWindow) return@forEach
                    val state = states.getOrPut(view) { State(view, view.background) }
                    state.fingerprint = artworkFingerprint
                    state.width = width
                    state.height = height
                    val local = capsuleWindow?.let { toLocalRect(it, view) }
                    state.capsule = local
                    val drawable = CapsuleCoverBackgroundDrawable(
                        bitmap = rendered,
                        cornerRadius = height / 2f,
                        targetRect = local,
                    )
                    state.drawable = drawable
                    view.background = drawable
                    attachReassert(state)
                    applied += 1
                }
                synchronized(targetsByOwner) { targetsByOwner[owner] = targets }
                HookLogger.i(
                    TAG,
                    "摘要态线性渐变背景已应用: targets=$applied/${targets.size}, " +
                        "size=${rendered.width}x${rendered.height}, " +
                        "capsule=${capsuleWindow?.toShortString() ?: "view-bounds"}, " +
                        "package=$packageName",
                )
            }
        }
    }

    /** 恢复指定岛的原生背景（样式切换/关闭时调用）。 */
    fun restoreFor(owner: View) {
        // 按记录的目标视图恢复：定位策略可能随状态变化，重新解析未必命中写入过的那些视图。
        val views = synchronized(targetsByOwner) { targetsByOwner.remove(owner) } ?: return
        views.forEach(::restoreBackground)
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
     * 岛子树层级探针（仅 debug 构建）：输出范围内每个视图的资源名/类名/尺寸/窗口位置，
     * 用于确认歌词态大胶囊实际由哪些视图构成，从而选对绘制目标。
     */
    private fun logIslandHierarchyProbe(scope: View) {
        if (!BuildConfig.DEBUG) return
        val signature = "${scope.javaClass.name}@${scope.width}x${scope.height}"
        if (probedScopes.contains(signature)) return
        probedScopes.add(signature)
        val parts = ArrayList<String>()
        val queue = ArrayDeque<View>()
        queue.addLast(scope)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val current = queue.removeFirst()
            visited += 1
            val location = IntArray(2)
            current.getLocationInWindow(location)
            parts += "[${resourceName(current) ?: "-"}|${current.javaClass.simpleName}|" +
                "${current.width}x${current.height}@${location[0]},${location[1]}]"
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) {
                    queue.addLast(current.getChildAt(index))
                }
            }
        }
        // 分片输出：单条日志超过 logcat 上限（约 4KB）会被静默丢弃，因此每片只放少量节点。
        val chunkSize = 8
        parts.chunked(chunkSize).forEachIndexed { index, chunk ->
            HookLogger.i(
                TAG,
                "岛层级探针[${index + 1}/${(parts.size + chunkSize - 1) / chunkSize}]: " +
                    chunk.joinToString(" "),
            )
        }
    }

    /**
     * 收集胶囊内需要绘制的分段视图：资源名以 area 开头的子视图（area_left / area_right 等）
     * 与岛背景视图（类名同时含 dynamicisland 与 background）。
     */
    private fun resolvePillTargets(scope: ViewGroup): List<View> {
        val result = ArrayList<View>()
        val queue = ArrayDeque<View>()
        queue.addLast(scope)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val current = queue.removeFirst()
            visited += 1
            val name = resourceName(current)
            // 只收集 area_* 分段视图（实测：area_left / area_cutout / area_right 横排拼成胶囊）。
            // 不能把 island_container(DynamicIslandBackgroundView) 等整窗宽背景层纳入：
            // 它宽 1080（整窗），会让并集变成 1080x124 从而把封面铺满整条顶部（溢出）。
            val isAreaSegment = name?.startsWith("area") == true
            if (current !== scope && isAreaSegment) result += current
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) {
                    queue.addLast(current.getChildAt(index))
                }
            }
        }
        // host 容器本身（big_container，横排内容区）就是整条内容区，优先作为绘制目标；
        // area_* 只是中段的占位区（实测仅 177~273 宽），单独用它们封面只会铺中间一截。
        if (scope.width > 0 && scope.height > 0) {
            result.add(0, scope)
        }
        return result
    }

    /**
     * 所有目标胶囊在窗口坐标下的并集，作为封面映射基准。
     * 返回 null 表示目标都没有效尺寸，调用方退回其它边界解析。
     */
    private fun unionWindowRect(views: List<View>): RectF? {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        views.forEach { view ->
            val width = view.width.takeIf { it > 0 } ?: return@forEach
            val height = view.height.takeIf { it > 0 } ?: return@forEach
            val location = IntArray(2)
            view.getLocationInWindow(location)
            left = minOf(left, location[0].toFloat())
            top = minOf(top, location[1].toFloat())
            right = maxOf(right, (location[0] + width).toFloat())
            bottom = maxOf(bottom, (location[1] + height).toFloat())
        }
        if (right <= left || bottom <= top) return null
        return RectF(left, top, right, bottom)
    }

    /**
     * 胶囊真实边界（窗口坐标）：优先取岛背景视图的 getActual* 边界，其次沿封面视图父链回溯。
     * 解析不到则返回 null，调用方退回各视图自身 bounds。
     */
    private fun resolveCapsuleWindowRect(scope: ViewGroup, owner: View): RectF? {
        findIslandBackgroundViewIn(scope)?.let { background ->
            actualWindowRect(background)?.let { return it }
        }
        var current: View? = owner
        while (current != null) {
            actualWindowRect(current)?.let { return it }
            current = current.parent as? View
        }
        return null
    }

    /** 系统 getActual* 给出的窗口坐标矩形；宽高字段兼容「右/下边」与「宽/高」两种语义。 */
    private fun actualWindowRect(view: View): RectF? {
        val left = getViewInt(view, "getActualLeft") ?: return null
        val top = getViewInt(view, "getActualTop") ?: return null
        val widthOrRight = getViewInt(view, "getActualWidth") ?: return null
        val heightOrBottom = getViewInt(view, "getActualHeight") ?: return null
        val right = if (widthOrRight > left) widthOrRight else left + widthOrRight
        val bottom = if (heightOrBottom > top) heightOrBottom else top + heightOrBottom
        if (right - left <= 0 || bottom - top <= 0) return null
        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    /** 窗口坐标矩形换算成指定视图的本地坐标。 */
    private fun toLocalRect(windowRect: RectF, view: View): RectF {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return RectF(
            windowRect.left - location[0],
            windowRect.top - location[1],
            windowRect.right - location[0],
            windowRect.bottom - location[1],
        )
    }

    private fun resourceName(view: View): String? {
        if (view.id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }

    private fun getViewInt(view: View, getterName: String): Int? = runCatching {
        view.javaClass.methods.firstOrNull {
            it.name == getterName && it.parameterTypes.isEmpty()
        }?.invoke(view).let { (it as? Number)?.toInt() }
    }.getOrNull()

    /**
     * 在给定范围内查找岛背景视图（类名同时含 dynamicisland 与 background）。
     * 命中多个时取最宽的那个，尽量覆盖整条胶囊。
     */
    private fun findIslandBackgroundViewIn(scope: View): View? {
        var best: View? = null
        val queue = ArrayDeque<View>()
        queue.addLast(scope)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val current = queue.removeFirst()
            visited += 1
            val className = current.javaClass.name
            if (className.contains("dynamicisland", ignoreCase = true) &&
                className.contains("background", ignoreCase = true)
            ) {
                val currentBest = best
                if (currentBest == null || current.width > currentBest.width) {
                    best = current
                }
            }
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) {
                    queue.addLast(current.getChildAt(index))
                }
            }
        }
        return best
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
    /** 胶囊真实绘制区域（视图本地坐标）；为空时按 Drawable bounds 绘制。 */
    private val targetRect: RectF? = null,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val target = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val rect = targetRect
        if (rect != null) {
            target.set(rect)
        } else {
            target.set(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
            )
        }
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
