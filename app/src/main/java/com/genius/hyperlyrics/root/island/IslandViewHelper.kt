package com.genius.hyperlyrics.root.island

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.root.HookEntry
import com.genius.hyperlyrics.root.island.view.MaxWidthFrameLayout
import com.genius.hyperlyrics.root.utils.HookLogger
import java.util.WeakHashMap

/**
 * 小米超级岛视图管理
 * 负责处理超级岛内部组件的查找、显隐切换及布局刷新
 */
object IslandViewHelper {

    private val SYSTEMUI_PKG_NAMES = arrayOf("miui.systemui.plugin", "com.android.systemui")

    /** 展开态探测的视图树遍历上限。 */
    private const val MAX_EXPANDED_SEARCH_NODES = 512

    /** 摘要胶囊宽度同步的延迟点（展开/收起后布局未必立刻完成）。 */
    private val SUMMARY_SYNC_DELAYS_MS = longArrayOf(0L, 120L, 300L, 700L)
    private val originalMargins = WeakHashMap<View, MarginSnapshot>()
    private val isRelayouting = ThreadLocal.withInitial { false }

    /**
     * 切换超级岛内部容器（如图标、文本容器）的可见性
     */
    @SuppressLint("DiscouragedApi")
    fun toggleContainer(root: ViewGroup, parentName: String, containerName: String, show: Boolean) {
        try {
            val res = root.resources
            val parent = findViewByName(root, parentName) as? ViewGroup
            
            if (parent != null) {
                for (pkg in SYSTEMUI_PKG_NAMES) {
                    val id = res.getIdentifier(containerName, "id", pkg)
                    if (id != 0) {
                        parent.findViewById<View>(id)?.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }
            }
        } catch (e: Exception) {
            HookLogger.e("IslandViewHelper", "切换容器可见性失败: container=$containerName", e)
        }
    }

    /**
     * 清除超级岛文本容器的边距
     */
    @SuppressLint("DiscouragedApi")
    fun clearTextContainerMargin(root: ViewGroup, parentName: String, clearStart: Boolean, clearEnd: Boolean) {
        try {
            val res = root.resources
            val parent = findViewByName(root, parentName) as? ViewGroup
            
            if (parent != null) {
                for (pkg in SYSTEMUI_PKG_NAMES) {
                    val id = res.getIdentifier("island_container_module_text", "id", pkg)
                    if (id != 0) {
                        val textContainer = parent.findViewById<View>(id)
                        if (textContainer != null) {
                            val lp = textContainer.layoutParams as? ViewGroup.MarginLayoutParams
                            if (lp != null) {
                                originalMargins.getOrPut(textContainer) {
                                    MarginSnapshot(lp.marginStart, lp.marginEnd)
                                }
                                if (clearStart) lp.marginStart = 0
                                if (clearEnd) lp.marginEnd = 0
                                textContainer.layoutParams = lp
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            HookLogger.e("IslandViewHelper", "清除边距失败: parent=$parentName", e)
        }
    }

    /** 收起中缝时保存的各区域原始布局（宽度 + 可见性），用于恢复。 */
    private val collapsedAreaLayouts = WeakHashMap<View, AreaLayoutSnapshot>()

    private class AreaLayoutSnapshot(val width: Int, val visibility: Int)

    /**
     * 摘要态「超级岛左侧内容 = 无内容」时，收起左区域 `area_left` 与中缝 `area_cutout`，
     * 让右侧内容（如歌词）铺满整条胶囊；其它配置或清理时恢复原样。
     *
     * 左/中/右三块是系统 `big_container`(LinearLayout) 的子视图（本机实测 146/177/146px，
     * 三者之和等于胶囊宽度），这里只调整布局参数与可见性，不改动系统原生资源。
     */
    fun setMiddleDividerCollapsed(root: ViewGroup, collapsed: Boolean): Boolean {
        val targets = listOfNotNull(
            findViewByName(root, "area_left"),
            findViewByName(root, "area_cutout"),
        )
        if (targets.isEmpty()) return false
        var changed = false
        targets.forEach { view ->
            val lp = view.layoutParams ?: return@forEach
            if (collapsed) {
                if (collapsedAreaLayouts.containsKey(view)) return@forEach
                collapsedAreaLayouts[view] = AreaLayoutSnapshot(lp.width, view.visibility)
                lp.width = 0
                view.layoutParams = lp
                view.visibility = View.GONE
                changed = true
            } else {
                val snapshot = collapsedAreaLayouts.remove(view) ?: return@forEach
                lp.width = snapshot.width
                view.layoutParams = lp
                view.visibility = snapshot.visibility
                changed = true
            }
        }
        if (changed) {
            HookLogger.i(
                "IslandViewHelper",
                "摘要态中缝收起=$collapsed areas=" +
                    targets.joinToString(",") { "${it.javaClass.simpleName}:${it.visibility}" },
            )
        }
        return changed
    }

    /**
     * 清理所有注入的视图并恢复系统原生组件
     */
    fun clearInjectedViews(rootView: ViewGroup) {
        IslandNativeSlotPlacement.restore(rootView)
        setMiddleDividerCollapsed(rootView, false)
        hideInjectedView(rootView, IslandProbeUtils.LEFT_TEST_VIEW_TAG)
        hideInjectedView(rootView, IslandProbeUtils.LEFT_TEST_WRAPPER_TAG)
        hideInjectedView(rootView, IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
        hideInjectedView(rootView, IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG)
        hideInjectedView(rootView, "HYPERLYRIC_TEST_VIEW_WRAPPER_LEFT")
        hideInjectedView(rootView, "HYPERLYRIC_TEST_VIEW_WRAPPER_RIGHT")
 
        // 恢复系统原有组件的可见性
        toggleContainer(rootView, "island_container_module_image_text_1", "island_container_module_icon", true)
        toggleContainer(rootView, "island_container_module_image_text_2", "island_container_module_icon", true)

        restoreTextContainerMargins(rootView, "island_container_module_image_text_1")
        restoreTextContainerMargins(rootView, "island_container_module_image_text_2")
        showOriginalTexts(rootView, "island_container_module_image_text_1")
        showOriginalTexts(rootView, "island_container_module_image_text_2")
    }

    private fun hideInjectedView(rootView: ViewGroup, tag: String) {        val view = rootView.findViewWithTag<View>(tag) ?: return
        val wrapper = view as? MaxWidthFrameLayout
        if (wrapper == null && view.javaClass.name == MaxWidthFrameLayout::class.java.name) {
            (view.parent as? ViewGroup)?.removeView(view)
            return
        }
        wrapper?.keepVisible = false
        view.visibility = View.GONE
    }

    /**
     * 显示原本被隐藏的原生文本视图
     */
    @SuppressLint("DiscouragedApi")
    fun showOriginalTexts(rootView: ViewGroup, parentName: String) {
        try {
            val res = rootView.resources
            val slotId = res.getIdentifier(parentName, "id", "miui.systemui.plugin")
            if (slotId == 0) return
            val parent = rootView.findViewById<ViewGroup>(slotId) ?: return
            
            val textSlotId = res.getIdentifier("island_container_module_text", "id", "miui.systemui.plugin")
            val container = if (textSlotId != 0) (parent.findViewById(textSlotId) ?: parent) else parent

            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                val tag = child.tag as? String ?: ""
                if (!tag.startsWith("HYPERLYRIC")) {
                    child.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            HookLogger.e("IslandViewHelper", "恢复原生文本失败: parent=$parentName", e)
        }
    }

    /**
     * 递归标记子树在下次 measure 时强制重新执行 onMeasure。
     *
     * 系统的 calculateBigIslandWidth 仅当左右区域包含原生 TextView 时才会
     * forceLayoutRecursively（见 applyPreMeasureMode），注入的歌词子树全部是
     * 自定义 View，不会触发该分支；而区域测量规格每次相同，View.measure 会因
     * 规格未变且无 FORCE_LAYOUT 标志直接短路，导致岛宽锁死在注入时刻。
     * 动态长度开启时必须在宽度重算前手动标记。
     */
    fun forceLayoutIslandAreas(rootView: ViewGroup) {
        val areaLeft = findViewByName(rootView, "area_left")
        val areaRight = findViewByName(rootView, "area_right")
        if (areaLeft == null && areaRight == null) {
            // 兜底：不同版本区域容器缺失时，直接标记注入模块所在的父容器
            forceLayoutRecursively(findViewByName(rootView, IslandProbeUtils.LEFT_PARENT_NAME))
            forceLayoutRecursively(findViewByName(rootView, IslandProbeUtils.RIGHT_PARENT_NAME))
            return
        }
        forceLayoutRecursively(areaLeft)
        forceLayoutRecursively(areaRight)
    }

    private fun forceLayoutRecursively(view: View?) {
        if (view == null) return
        view.forceLayout()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                forceLayoutRecursively(view.getChildAt(i))
            }
        }
    }

    internal fun isDynamicWidthEnabled(): Boolean {
        return HookEntry.instance?.prefs?.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH,
            RootConstants.DEFAULT_HOOK_ISLAND_DYNAMIC_WIDTH
        ) == true
    }

    /**
     * 动态长度开启时，在宽度重算前标记左右区域子树强制重新测量，
     * 覆盖 triggerSystemRelayout 与系统自发 calculateBigIslandWidth 两条路径。
     */
    fun forceLayoutIslandAreasIfDynamicWidth(rootView: ViewGroup) {
        if (isDynamicWidthEnabled()) {
            forceLayoutIslandAreas(rootView)
        }
    }

    /**
     * 超级岛当前是否处于「展开态」（媒体大卡片真正铺在屏幕上）。
     *
     * 依据展开态大卡片背景视图 `media_bg_view`(MusicBgView) 的 visibility + 尺寸判断。
     * 折叠态下该视图不可见（系统 onVisibilityChanged 实测 visibility 在 0/4 间切换），
     * 展开后 visibility=VISIBLE 且尺寸为 1001x462。
     *
     * 用途：展开大窗时顶部摘要胶囊应恢复系统原生短胶囊长度（展开态不注入歌词），
     * 收起后再注入歌词恢复歌词长胶囊。
     */
    fun isIslandExpandedOnScreen(root: View): Boolean {
        val scope = root.rootView ?: root
        val queue = ArrayDeque<View>()
        queue.addLast(scope)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_EXPANDED_SEARCH_NODES) {
            val current = queue.removeFirst()
            visited += 1
            if (resourceEntryName(current) == "media_bg_view" && isViewVisibleOnScreen(current)) {
                return true
            }
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) {
                    queue.addLast(current.getChildAt(index))
                }
            }
        }
        return false
    }

    /**
     * 视图是否处于可见状态。
     *
     * 注意：本机（HyperOS 移植包）实测 `mediaBgView.isShown` **恒为 false**（即使 visibility=VISIBLE、
     * 已铺在屏幕上），`getGlobalVisibleRect` 同样不可靠（同样依赖 isShown）。因此这里只用
     * visibility + 尺寸判定，不能用 isShown / getGlobalVisibleRect，否则展开态永远判不出来。
     */
    private fun isViewVisibleOnScreen(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        return view.width > 0 && view.height > 0
    }

    @SuppressLint("DiscouragedApi")
    private fun resourceEntryName(view: View): String? {
        if (view.id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }

    /**
     * 从注入的槽位视图向上查找超级岛内容视图并触发布局刷新。
     * 用于换句/预览提升动画把内容更新延迟落地后的第二次岛宽重算——
     * 否则内容应用返回时的立即测量只能量到上一行宽度。
     * 开关状态在触发时实时读取，找不到宿主视图时静默返回。
     */
    fun triggerSystemRelayoutForDescendant(view: View, requireDynamicWidth: Boolean = true) {
        if (requireDynamicWidth && !isDynamicWidthEnabled()) return
        var parent = view.parent
        while (parent is View) {
            if (parent is ViewGroup && parent.javaClass.methods.any {
                    it.name == "updateBigIslandViewWidth" || it.name == "calculateBigIslandWidth"
                }
            ) {
                triggerSystemRelayout(parent)
                return
            }
            parent = parent.parent
        }
    }

    /**
     * 超级岛展开/收起后延迟触发若干次摘要胶囊宽度重算。
     *
     * 展开态下 [IslandWidthHooker.CalculateWidthHook] 会清空注入、按原生内容算出系统原生短胶囊；
     * 收起态下会重新注入歌词、恢复歌词长胶囊。`onVisibilityChanged` 触发时布局往往尚未完成，
     * 因此按多个延迟点重复触发（幂等，可安全重入）。
     */
    fun scheduleSummaryWidthSync(anchor: View) {
        HookLogger.i(
            "IslandViewHelper",
            "摘要胶囊展开/收起同步: expanded=${isIslandExpandedOnScreen(anchor)}",
        )
        SUMMARY_SYNC_DELAYS_MS.forEach { delay ->
            if (delay <= 0L) {
                triggerSystemRelayoutForDescendant(anchor, requireDynamicWidth = false)
            } else {
                anchor.postDelayed(
                    { triggerSystemRelayoutForDescendant(anchor, requireDynamicWidth = false) },
                    delay,
                )
            }
        }
    }

    /**
     * 触发超级岛系统的布局刷新
     *
     * 使用 ThreadLocal 防止重入：triggerSystemRelayout 调用的系统方法可能被
     * Hook 拦截后再次触发 triggerSystemRelayout，导致无限递归。
     */
    fun triggerSystemRelayout(islandView: ViewGroup) {
        if (isRelayouting.get() == true) return
        HookLogger.d("IslandViewHelper","正在触发布局刷新")
        isRelayouting.set(true)
        try {
            runCatching {
                forceLayoutIslandAreasIfDynamicWidth(islandView)
                val viewClass = islandView.javaClass
                // 优先尝试 updateBigIslandViewWidth
                val updateWidthMethod = viewClass.methods.find { it.name == "updateBigIslandViewWidth" }
                if (updateWidthMethod != null) {
                    updateWidthMethod.invoke(islandView)
                } else {
                    // 兜底尝试 calculateBigIslandWidth
                    viewClass.methods.find { it.name == "calculateBigIslandWidth" }?.invoke(islandView)
                }
            }.onFailure { e ->
                HookLogger.e("IslandViewHelper", "超级岛布局刷新失败", e)
            }
        } finally {
            isRelayouting.set(false)
        }
    }

    /**
     * 根据名称寻找 View（支持多包名兜底）
     */
    @SuppressLint("DiscouragedApi")
    fun findViewByName(root: ViewGroup, name: String): View? {
        val res = root.resources
        for (pkg in SYSTEMUI_PKG_NAMES) {
            val id = res.getIdentifier(name, "id", pkg)
            if (id != 0) {
                val v = root.findViewById<View>(id)
                if (v != null) return v
            }
        }
        return null
    }

    private fun restoreTextContainerMargins(rootView: ViewGroup, parentName: String) {
        val parent = findViewByName(rootView, parentName) as? ViewGroup ?: return
        val container = findViewByName(parent, "island_container_module_text") ?: return
        val snapshot = originalMargins[container] ?: return
        val lp = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.marginStart != snapshot.marginStart || lp.marginEnd != snapshot.marginEnd) {
            lp.marginStart = snapshot.marginStart
            lp.marginEnd = snapshot.marginEnd
            container.layoutParams = lp
        }
    }

    private data class MarginSnapshot(
        val marginStart: Int,
        val marginEnd: Int
    )
}
