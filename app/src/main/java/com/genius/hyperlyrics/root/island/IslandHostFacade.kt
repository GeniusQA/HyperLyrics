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

        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.LEFT_PARENT_NAME, "island_container_module_icon", showAlbum)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, "island_container_module_icon", showRhythm)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.LEFT_PARENT_NAME, IslandProbeUtils.TEXT_CONTAINER_NAME, true)
        IslandViewHelper.toggleContainer(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, IslandProbeUtils.TEXT_CONTAINER_NAME, true)

        if (!showAlbum) {
            IslandViewHelper.clearTextContainerMargin(rootView, IslandProbeUtils.LEFT_PARENT_NAME, clearStart = true, clearEnd = false)
        }
        if (!showRhythm) {
            IslandViewHelper.clearTextContainerMargin(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, clearStart = false, clearEnd = true)
        }

        // 左侧内容为「无内容」时，收掉左区域 area_left 与中缝 area_cutout 的占位，
        // 让右侧内容（歌词）铺满整条胶囊；其它配置恢复原生布局。
        // 注意：这里只改 LayoutParams、不隐藏视图，也不额外触发重排，避免破坏测量/抖动。
        val leftContent = prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT,
            RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT,
        )
        IslandViewHelper.applySingleSideFullWidth(rootView, leftContent == 0)
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
