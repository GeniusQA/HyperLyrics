package com.genius.hyperlyrics.root.island

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.root.mediacard.notification.background.MediaBackgroundRendererPool
import com.genius.hyperlyrics.root.utils.HookLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 摘要态超级岛「线性渐变」背景（音频封面样式 = 线性渐变）。
 *
 * 复用通知中心/焦点通知卡片的线性渐变渲染（封面整幅融入 + 封面取色渐变压暗，
 * 即 [RootConstants.NOTIFICATION_MEDIA_BACKGROUND_STYLE_LINEAR_GRADIENT]），渲染成岛尺寸位图后
 * 水平镜像，以适配超级岛「封面缩略图在左、歌词文字在右」的布局：封面纹理落在左侧缩略图附近，
 * 右侧过渡为封面取色底色，保证文字可读且与当前音频封面配色融合。
 */
internal object IslandLinearGradientBackgroundApplier {
    private const val TAG = "IslandLinearGradientBg"

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
    }

    /**
     * 应用线性渐变背景。
     * @param owner 岛的封面 ImageView（用于定位所属岛的背景视图）
     * @param artwork 当前封面 Drawable，为空时回退到播放器应用图标
     */
    fun apply(owner: View, artwork: Drawable?, packageName: String?) {
        val backgroundView = resolveIslandBackgroundView(owner) ?: return
        val width = backgroundView.width.takeIf { it > 0 } ?: backgroundView.measuredWidth
        val height = backgroundView.height.takeIf { it > 0 } ?: backgroundView.measuredHeight
        if (width <= 0 || height <= 0 || !backgroundView.isAttachedToWindow) return

        val state = states.getOrPut(backgroundView) {
            State(backgroundView, backgroundView.background)
        }
        val artworkFingerprint = artworkFingerprint(artwork)
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
            val renderer = runCatching { MediaBackgroundRendererPool.get(context.classLoader) }
                .onFailure { error ->
                    HookLogger.e(TAG, "初始化线性渐变背景渲染器失败", error)
                }
                .getOrNull() ?: return@execute
            val rendered = runCatching {
                renderer.renderDrawable(
                    context = context,
                    artworkDrawable = artwork,
                    packageName = packageName.orEmpty(),
                    style = RootConstants.NOTIFICATION_MEDIA_BACKGROUND_STYLE_LINEAR_GRADIENT,
                    blurAmount = 0,
                    autoInvert = false,
                    softCoverTone = RootConstants.MEDIA_SOFT_COVER_TONE_DARK,
                    width = width,
                    height = height,
                )
            }.onFailure { error ->
                HookLogger.e(TAG, "渲染摘要态线性渐变背景失败", error)
            }.getOrNull() ?: return@execute

            val source = rendered.bitmap
            val mirrored = mirrorHorizontally(source)
            source.recycle()
            if (mirrored == null) return@execute

            backgroundView.post {
                if (states[backgroundView] !== state) {
                    mirrored.recycle()
                    return@post
                }
                backgroundView.background = BitmapDrawable(backgroundView.resources, mirrored)
                HookLogger.d(
                    TAG,
                    "摘要态线性渐变背景已应用: size=${mirrored.width}x${mirrored.height}, " +
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
        view.post {
            view.background = state.original
        }
    }

    /** 封面指纹：优先按位图实例，避免同一 Drawable 对象换歌时漏刷新。 */
    private fun artworkFingerprint(artwork: Drawable?): Long {
        val bitmap = (artwork as? BitmapDrawable)?.bitmap
        return if (bitmap != null) {
            System.identityHashCode(bitmap).toLong()
        } else {
            System.identityHashCode(artwork).toLong()
        }
    }

    private fun mirrorHorizontally(source: Bitmap): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null
        return runCatching {
            val mirrored = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val matrix = Matrix().apply { setScale(-1f, 1f, source.width / 2f, source.height / 2f) }
            Canvas(mirrored).drawBitmap(source, matrix, null)
            mirrored
        }.onFailure { error ->
            HookLogger.e(TAG, "镜像线性渐变背景失败", error)
        }.getOrNull()
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
