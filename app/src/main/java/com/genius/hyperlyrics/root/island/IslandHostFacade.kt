package com.genius.hyperlyrics.root.island

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.ViewGroup
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.common.media.MediaMetadataHelper
import com.genius.hyperlyrics.root.HookIslandGlow
import com.genius.hyperlyrics.root.utils.HookLogger

internal object IslandHostFacade {
    private var loggedCutoutInfo = false

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

        // 左侧内容为「无内容」时，只清掉左文本容器两侧边距、让系统尽量不占左侧空间
        // （仅改可见性/边距，不碰 area_* 布局骨架，避免破坏测量）。
        val leftEmpty = prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT,
            RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT,
        ) == 0

        // 「无内容」是指左侧不放我们的文字内容，**不等于连封面也不显示**：
        // 此时应保留系统原生专辑 logo（是否显示由「音频封面」开关决定）。
        // 此前写成 showAlbum && !leftEmpty，会把左侧图标容器一并 GONE，导致封面 logo 消失。
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.LEFT_PARENT_NAME, "island_container_module_icon", showAlbum)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, "island_container_module_icon", showRhythm)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.LEFT_PARENT_NAME, IslandProbeUtils.TEXT_CONTAINER_NAME, true)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, IslandProbeUtils.TEXT_CONTAINER_NAME, true)

        if (showAlbum) {
            // 专辑 logo 可见：必须保留系统给文本容器留的原生边距，否则文本容器会与图标同一起点、
            // 注入的歌词直接压在专辑 logo 上（本机实测：图标 61x55@437 与文本容器 207x90@437 同 x）。
            // 旧逻辑因为会把图标一并隐藏，所以顺手清掉了边距；图标恢复后必须改回原生边距。
            IslandViewHelper.restoreTextContainerMargins(rootView, IslandProbeUtils.LEFT_PARENT_NAME)
        } else {
            // 图标隐藏：清掉文本容器两侧边距，让歌词尽量占满左侧。
            IslandViewHelper.clearTextContainerMargin(rootView, IslandProbeUtils.LEFT_PARENT_NAME, clearStart = true, clearEnd = leftEmpty)
        }
        if (!showRhythm) {
            IslandViewHelper.clearTextContainerMargin(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, clearStart = false, clearEnd = true)
        }
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
