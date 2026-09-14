package com.genius.hyperlyrics.root.mediacard

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.genius.hyperlyrics.BuildConfig
import com.genius.hyperlyrics.root.utils.HookLogger
import java.util.WeakHashMap

/**
 * 封面匀速旋转控制器，锁屏媒体卡片与超级岛共用。
 *
 * 之前存在两份几乎相同的实现（本类与 root.island.IslandAlbumCoverRotationController），
 * 差异仅在于超级岛需要「中心锚点校正」与「全局播放态切换」，现统一为可选参数：
 * - [centerPivot]：按 View 尺寸把旋转锚点校正到中心（超级岛圆形封面依赖）；
 * - [resetRotationOnDetach]：窗口分离时是否把角度归零（超级岛需要，锁屏卡片不需要）；
 * - [setPlaybackActive]：统一切换所有已注册封面的播放态。
 */
internal object MediaCoverRotationController {
    private const val TAG = "CoverRotation"
    private const val ROTATION_DURATION_MS = 20_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = WeakHashMap<ImageView, RotationState>()

    @Volatile
    private var globalPlaybackActive = true

    private val attachStateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            val imageView = view as? ImageView ?: return
            states[imageView]?.let { startIfNeeded(imageView, it) }
        }

        override fun onViewDetachedFromWindow(view: View) {
            val imageView = view as? ImageView ?: return
            states[imageView]?.let { stopAnimator(imageView, it, it.resetRotationOnDetach) }
        }
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val imageView = view as? ImageView ?: return@OnLayoutChangeListener
        if (states[imageView]?.centerPivot == true) ensureCenteredPivot(imageView)
    }

    fun attach(
        view: ImageView,
        playbackActive: Boolean = globalPlaybackActive,
        centerPivot: Boolean = false,
        resetRotationOnDetach: Boolean = false
    ) {
        runOnMain {
            val isNew = !states.containsKey(view)
            val state = states.getOrPut(view) {
                view.addOnAttachStateChangeListener(attachStateListener)
                RotationState()
            }
            // 只在首次或「无锚点校正 → 需要锚点校正」时注册，避免重复注册导致移除不干净。
            if (centerPivot && (isNew || !state.centerPivot)) {
                view.addOnLayoutChangeListener(layoutChangeListener)
            }
            state.playbackActive = playbackActive
            state.centerPivot = centerPivot
            state.resetRotationOnDetach = resetRotationOnDetach
            if (playbackActive) {
                startIfNeeded(view, state)
            } else {
                pause(state)
            }
        }
    }

    fun detach(view: ImageView) {
        runOnMain {
            val state = states.remove(view) ?: return@runOnMain
            view.removeOnAttachStateChangeListener(attachStateListener)
            view.removeOnLayoutChangeListener(layoutChangeListener)
            stopAnimator(view, state, resetRotation = true)
        }
    }

    /** 统一切换所有已注册封面的播放态（超级岛随播放状态整体启停）。 */
    fun setPlaybackActive(active: Boolean) {
        globalPlaybackActive = active
        runOnMain {
            states.toList().forEach { (view, state) ->
                state.playbackActive = active
                if (active) {
                    startIfNeeded(view, state)
                } else {
                    pause(state)
                }
            }
        }
    }

    fun cleanup() {
        runOnMain {
            states.toList().forEach { (view, state) ->
                view.removeOnAttachStateChangeListener(attachStateListener)
                view.removeOnLayoutChangeListener(layoutChangeListener)
                stopAnimator(view, state, resetRotation = true)
            }
            states.clear()
        }
    }

    private fun startIfNeeded(view: ImageView, state: RotationState) {
        if (!state.playbackActive || !view.isAttachedToWindow) return

        if (state.centerPivot) ensureCenteredPivot(view)

        val existing = state.animator
        if (existing != null) {
            if (existing.isPaused) existing.resume()
            return
        }

        state.animator = ObjectAnimator.ofFloat(
            view,
            View.ROTATION,
            view.rotation,
            view.rotation + 360f
        ).apply {
            duration = ROTATION_DURATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    private fun pause(state: RotationState) {
        state.animator?.takeIf { it.isStarted && !it.isPaused }?.pause()
    }

    private fun stopAnimator(
        view: ImageView,
        state: RotationState,
        resetRotation: Boolean
    ) {
        state.animator?.cancel()
        state.animator = null
        if (resetRotation) view.rotation = 0f
    }

    private fun ensureCenteredPivot(view: ImageView) {
        val width = view.width
        val height = view.height
        val pivot = CoverRotationGeometry.centeredPivot(width, height) ?: return
        if (view.pivotX == pivot.x && view.pivotY == pivot.y) return
        view.pivotX = pivot.x
        view.pivotY = pivot.y
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                TAG,
                "旋转封面锚点已校正: view=${System.identityHashCode(view)}, " +
                    "pivot=${pivot.x}x${pivot.y}, size=${width}x$height",
            )
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private data class RotationState(
        var playbackActive: Boolean = true,
        var centerPivot: Boolean = false,
        var resetRotationOnDetach: Boolean = false,
        var animator: ObjectAnimator? = null
    )
}
