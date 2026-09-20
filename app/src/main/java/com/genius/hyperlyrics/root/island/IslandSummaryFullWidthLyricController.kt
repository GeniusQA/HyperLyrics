package com.genius.hyperlyrics.root.island

import android.view.View
import android.view.ViewGroup
import com.genius.hyperlyrics.root.utils.HookLogger
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 摘要态「超级岛左侧 = 无内容」时，让右侧注入的歌词**绘制**铺满整条胶囊（跨过中缝）。
 *
 * 设计原则：**只做绘制层调整，不改任何视图的测量**——
 *  1. 以「胶囊左端」为歌词绘制起点：对注入 wrapper 设 `translationX`（只影响绘制，不触发 relayout）；
 *  2. 逐级关闭 wrapper → 胶囊之间各容器的 `clipChildren`/`clipToPadding`，
 *     否则歌词会被中间那个只有几十像素宽的小容器裁掉；
 *  3. **保留** `big_container` / `big_island_view` 自身的裁剪 —— 它们就是胶囊范围，
 *     于是歌词恰好只画在胶囊内，既铺满又不会溢出到状态栏其它区域。
 *
 * 位置变化通过 `OnLayoutChangeListener` 增量同步（只在布局变化时重算一次 `translationX`）。
 * 整个过程不产生 `requestLayout` 回传，因此不会出现此前那种"收放翻转 → 卡顿"。
 */
internal object IslandSummaryFullWidthLyricController {
    private const val TAG = "IslandFullWidthLyric"

    /** 已启用状态（按岛内容视图记录，便于幂等与恢复）。 */
    private val appliedStates = WeakHashMap<View, AppliedState>()

    private class AppliedState(
        val capsule: View,
        val wrapper: View,
        val clipSnapshots: List<Triple<ViewGroup, Boolean, Boolean>>,
        val listener: View.OnLayoutChangeListener,
    )

    /** 启用（幂等）。返回是否本次新启用。 */
    fun apply(rootView: ViewGroup): Boolean {
        if (appliedStates.containsKey(rootView)) return false
        val wrapper = rootView.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG) ?: return false
        val capsule = IslandViewHelper.findViewByName(rootView, "big_island_view")
            ?: IslandViewHelper.findViewByName(rootView, "big_container")
            ?: return false

        // wrapper 自身不裁剪；从它的父级一路到胶囊（不含胶囊）逐级关闭裁剪。
        val clipSnapshots = ArrayList<Triple<ViewGroup, Boolean, Boolean>>()
        (wrapper as? ViewGroup)?.let {
            clipSnapshots += Triple(it, it.clipChildren, it.clipToPadding)
            it.clipChildren = false
            it.clipToPadding = false
        }
        var parent: View? = wrapper.parent as? View
        var depth = 0
        while (parent != null && parent !== capsule && depth < 12) {
            if (parent is ViewGroup) {
                clipSnapshots += Triple(parent, parent.clipChildren, parent.clipToPadding)
                parent.clipChildren = false
                parent.clipToPadding = false
            }
            parent = parent.parent as? View
            depth += 1
        }

        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncOffset(capsule, wrapper) }
        val state = AppliedState(capsule, wrapper, clipSnapshots, listener)
        appliedStates[rootView] = state
        capsule.addOnLayoutChangeListener(listener)
        wrapper.addOnLayoutChangeListener(listener)
        syncOffset(capsule, wrapper)
        HookLogger.i(
            TAG,
            "已启用全宽歌词绘制: 裁剪链=${clipSnapshots.size} 层, capsule=${capsule.javaClass.simpleName}",
        )
        return true
    }

    /** 关闭并恢复原生（幂等）。返回是否确实恢复了。 */
    fun restore(rootView: ViewGroup): Boolean {
        val state = appliedStates.remove(rootView) ?: return false
        state.capsule.removeOnLayoutChangeListener(state.listener)
        state.wrapper.removeOnLayoutChangeListener(state.listener)
        state.clipSnapshots.forEach { (group, clipChildren, clipToPadding) ->
            group.clipChildren = clipChildren
            group.clipToPadding = clipToPadding
        }
        state.wrapper.translationX = 0f
        HookLogger.i(TAG, "已关闭全宽歌词绘制，恢复原生裁剪与位移")
        return true
    }

    /**
     * 把歌词绘制起点对齐到胶囊左端：`translationX = -(wrapperX - capsuleX)`。
     * 仅当数值确有变化时写入，避免无意义的 invalidate。
     */
    private fun syncOffset(capsule: View, wrapper: View) {
        if (capsule.width <= 0 || wrapper.width <= 0) return
        val capsuleLocation = IntArray(2)
        capsule.getLocationInWindow(capsuleLocation)
        val wrapperLocation = IntArray(2)
        wrapper.getLocationInWindow(wrapperLocation)
        if (capsuleLocation[1] < -1000) return // 岛被暂存到屏幕外，位置无意义
        val target = -(wrapperLocation[0] - capsuleLocation[0]).toFloat()
        if (abs(wrapper.translationX - target) > 0.5f) {
            wrapper.translationX = target
        }
    }
}
