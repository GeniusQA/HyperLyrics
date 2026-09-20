package com.genius.hyperlyrics.root.island

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.common.media.MediaMetadataHelper
import com.genius.hyperlyrics.root.HookIslandGlow
import com.genius.hyperlyrics.root.utils.HookLogger
import java.util.WeakHashMap

internal object IslandHostFacade {
    private const val TAG = "IslandHostFacade"
    private var loggedCutoutInfo = false

    /** 阶段 2 前置探针：每个岛内容视图只输出一次。 */
    private val probedFullWidthRoots = WeakHashMap<View, Boolean>()

    fun logCameraCutoutInfo(rootView: ViewGroup) {
        if (loggedCutoutInfo) return
        val cutoutView = IslandViewHelper.findViewByName(rootView, "area_cutout")
        if (cutoutView != null) {
            val location = IntArray(2)
            cutoutView.getLocationOnScreen(location)
            HookLogger.d(
                "IslandHostFacade",
                "摄像头挖孔宽度=${cutoutView.width}px，x=${location[0]}"
            )
        } else {
            HookLogger.d("IslandHostFacade", "未找到摄像头挖孔视图")
        }
        loggedCutoutInfo = true
    }

    fun applyHostSettings(rootView: ViewGroup, prefs: SharedPreferences) {
        val showAlbum = prefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM,
            RootConstants.DEFAULT_HOOK_ISLAND_LEFT_ALBUM
        )
        val showRhythm = prefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON,
            RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_ICON
        )

        // 左侧内容为「无内容」时，左侧图标容器彻底隐藏、并清掉左文本容器两侧边距，
        // 让系统尽量不占左侧空间（仅改可见性/边距，不碰 area_* 布局骨架，避免破坏测量）。
        val leftEmpty = prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT,
            RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT,
        ) == 0

        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.LEFT_PARENT_NAME, "island_container_module_icon", showAlbum && !leftEmpty)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, "island_container_module_icon", showRhythm)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.LEFT_PARENT_NAME, IslandProbeUtils.TEXT_CONTAINER_NAME, true)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, IslandProbeUtils.TEXT_CONTAINER_NAME, true)

        if (!showAlbum || leftEmpty) {
            IslandViewHelper.clearTextContainerMargin(rootView, IslandProbeUtils.LEFT_PARENT_NAME, clearStart = true, clearEnd = leftEmpty)
        }
        if (!showRhythm) {
            IslandViewHelper.clearTextContainerMargin(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, clearStart = false, clearEnd = true)
        }

        if (leftEmpty) {
            logFullWidthProbe(rootView)
            // 阶段 2：让右侧歌词的「绘制」铺满整条胶囊（跨过中缝）。
            // 只做 translationX + clipChildren 调整，不触发任何测量/重算。
            IslandSummaryFullWidthLyricController.apply(rootView)
        } else {
            IslandSummaryFullWidthLyricController.restore(rootView)
        }
    }

    /**
     * 阶段 2 前置探针（**只读，不修改任何视图状态**）。
     *
     * 量取「把歌词按整条胶囊宽度渲染」所需的真实数据：容器/区域/wrapper 的几何与
     * LayoutParams、右区域相对胶囊左端的偏移 dx、以及从 wrapper 到 big_island_view 的
     * clipChildren/clipToPadding 链。每个岛内容视图只输出一次。
     */
    private fun logFullWidthProbe(rootView: ViewGroup) {
        if (probedFullWidthRoots.put(rootView, true) != null) return
        runCatching {
            val bigContainer = IslandViewHelper.findViewByName(rootView, "big_container")
            val bigIslandView = IslandViewHelper.findViewByName(rootView, "big_island_view")
            val areaLeft = IslandViewHelper.findViewByName(rootView, "area_left")
            val areaCutout = IslandViewHelper.findViewByName(rootView, "area_cutout")
            val areaRight = IslandViewHelper.findViewByName(rootView, "area_right")
            val rightModule = IslandViewHelper.findViewByName(rootView, IslandProbeUtils.RIGHT_PARENT_NAME)
            val textContainer = (rightModule as? ViewGroup)?.let {
                IslandViewHelper.findViewByName(it, "island_container_module_text")
            }
            val wrapper = rootView.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG)

            HookLogger.i(
                TAG,
                "阶段2探针[容器] big_island_view=${describeGeometry(bigIslandView)} " +
                    "big_container=${describeGeometry(bigContainer)}",
            )
            HookLogger.i(
                TAG,
                "阶段2探针[区域] left=${describeGeometry(areaLeft)} cutout=${describeGeometry(areaCutout)} " +
                    "right=${describeGeometry(areaRight)}",
            )
            HookLogger.i(
                TAG,
                "阶段2探针[右槽] module=${describeLayout(rightModule)} " +
                    "textContainer=${describeLayout(textContainer)}",
            )
            HookLogger.i(TAG, "阶段2探针[wrapper] geometry=${describeGeometry(wrapper)} ${describeLayout(wrapper)}")

            val capsule = windowRectOf(bigIslandView ?: bigContainer)
            val rightRect = windowRectOf(areaRight)
            if (capsule != null && rightRect != null) {
                HookLogger.i(
                    TAG,
                    "阶段2探针[几何] capsuleX=${capsule[0]} capsuleW=${capsule[2]} " +
                        "areaRightX=${rightRect[0]} areaRightW=${rightRect[2]} " +
                        "dx=${rightRect[0] - capsule[0]} 可用于整宽=${capsule[2]}",
                )
            }

            var current: View? = wrapper
            var depth = 0
            while (current != null && depth < 8) {
                val group = current as? ViewGroup
                HookLogger.i(
                    TAG,
                    "阶段2探针[裁剪$depth] ${current.javaClass.simpleName} " +
                        "clipChildren=${group?.clipChildren} clipToPadding=${group?.clipToPadding} " +
                        "translationX=${current.translationX} size=${current.width}x${current.height}",
                )
                if (current === bigIslandView) break
                current = current.parent as? View
                depth += 1
            }
        }.onFailure { HookLogger.e(TAG, "阶段2探针失败", it) }
    }

    private fun windowRectOf(view: View?): IntArray? {
        if (view == null) return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return intArrayOf(location[0], location[1], view.width, view.height)
    }

    private fun describeGeometry(view: View?): String {
        val rect = windowRectOf(view) ?: return "null"
        return "${view!!.javaClass.simpleName} ${rect[2]}x${rect[3]}@${rect[0]},${rect[1]} vis=${view.visibility}"
    }

    private fun describeLayout(view: View?): String {
        if (view == null) return "null"
        val lp = view.layoutParams
        return "${view.javaClass.simpleName} lpW=${lp?.width} lpH=${lp?.height} " +
            "size=${view.width}x${view.height} transX=${view.translationX}"
    }

    fun clearAndRefresh(rootView: ViewGroup) {
        IslandViewHelper.clearInjectedViews(rootView)
        IslandProgressGlowController.clear(rootView)
        IslandViewHelper.triggerSystemRelayout(rootView)
    }

    fun clearInjectedViews(rootView: ViewGroup) {
        IslandViewHelper.clearInjectedViews(rootView)
        IslandProgressGlowController.clear(rootView)
    }

    fun triggerSystemRelayout(rootView: ViewGroup) {
        IslandViewHelper.triggerSystemRelayout(rootView)
    }

    fun injectHostGlow(viewGroup: ViewGroup, islandData: Any?, prefs: SharedPreferences) {
        HookIslandGlow.injectAndTriggerGlow(viewGroup, islandData, prefs)
    }

    fun updateHostGlow(rootView: ViewGroup, albumArt: Bitmap?, prefs: SharedPreferences) {
        HookIslandGlow.updateMusicGlow(rootView, albumArt, prefs)
    }

    fun updateProgressGlow(
        rootView: ViewGroup,
        packageName: String,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        prefs: SharedPreferences
    ) {
        IslandProgressGlowController.update(rootView, packageName, mediaInfo, prefs)
    }

    fun updateProgressGlow(
        rootView: ViewGroup,
        packageName: String,
        prefs: SharedPreferences
    ) {
        IslandProgressGlowController.update(rootView, packageName, null, prefs)
    }
}
