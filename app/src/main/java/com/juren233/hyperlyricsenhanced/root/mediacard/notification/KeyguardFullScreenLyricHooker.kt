package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.app.KeyguardManager
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.SongPreprocessor
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

/**
 * 全屏锁屏歌词（ColorOS 风格）：
 * 向锁屏窗口根视图（NotificationShadeWindowView）最底层注入全屏歌词视图，
 * 在时钟下方与媒体卡片上方之间的区域滚动显示歌词与翻译。
 *
 * 数据复用 LyriconDataBridge（完整歌词列表 + 播放进度），渲染由
 * [KeyguardFullScreenLyricView] 自绘完成，不修改任何卡片/通知几何。
 */
object KeyguardFullScreenLyricHooker {
    private const val TAG = "KeyguardFullScreenLyric"
    private const val SHADE_WINDOW_CLASS =
        "com.android.systemui.shade.NotificationShadeWindowView"
    private const val STATUS_VIEW_ID = "keyguard_status_view"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val shadeWindows = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    )

    @Volatile
    private var module: XposedModule? = null

    // 媒体卡片顶部（屏幕坐标），由 NotificationMediaAodLyricHooker 上报
    @Volatile
    private var mediaCardTopOnScreen: Int = -1

    private val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    fun initialize(xposedModule: XposedModule) {
        module = xposedModule
    }

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        initialize(xposedModule)
        if (!hookedClassLoaders.add(classLoader)) return
        val shadeClass = runCatching { classLoader.loadClass(SHADE_WINDOW_CLASS) }
            .onFailure {
                HookLogger.w(TAG, "跳过全屏锁屏歌词 Hook: reason=shade_view_unavailable")
                hookedClassLoaders.remove(classLoader)
            }
            .getOrNull() ?: return
        val onAttached = shadeClass.declaredMethods.firstOrNull {
            it.name == "onAttachedToWindow" && it.parameterCount == 0
        } ?: run {
            HookLogger.w(TAG, "跳过全屏锁屏歌词 Hook: reason=attach_method_unavailable")
            hookedClassLoaders.remove(classLoader)
            return
        }
        runCatching {
            xposedModule.deoptimize(onAttached)
            xposedModule.hook(onAttached).intercept(ShadeAttachHook())
            HookLogger.i(TAG, "全屏锁屏歌词 Hook 已初始化")
        }.onFailure {
            hookedClassLoaders.remove(classLoader)
            HookLogger.e(TAG, "安装全屏锁屏歌词 Hook 失败", it)
        }
    }

    private class ShadeAttachHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            (chain.thisObject as? View)?.let { view ->
                runOnMain { ensureLyricLayer(view) }
            }
            return result
        }
    }

    private fun ensureLyricLayer(shadeWindow: View) {
        if (shadeWindow !is ViewGroup) return
        synchronized(shadeWindows) {
            if (shadeWindows.contains(shadeWindow)) return
            shadeWindows.add(shadeWindow)
        }
        val lyricView = KeyguardFullScreenLyricView(shadeWindow.context)
        lyricView.visibility = View.GONE
        try {
            // 插到通知堆栈层（NotificationStackScrollLayout）之前：
            // 位于各遮罩层（scrim）之上避免歌词被压暗，又不会遮挡时钟与通知卡片。
            var insertIndex = shadeWindow.childCount
            for (index in 0 until shadeWindow.childCount) {
                if (shadeWindow.getChildAt(index).javaClass.name
                        .contains("NotificationStackScrollLayout")
                ) {
                    insertIndex = index
                    break
                }
            }
            if (BuildConfig.DEBUG) {
                for (index in 0 until shadeWindow.childCount) {
                    val child = shadeWindow.getChildAt(index)
                    HookLogger.i(
                        TAG,
                        "SHADE_LAYER_DUMP index=$index view=${child.javaClass.name} " +
                            "id=${child.id} visibility=${child.visibility} " +
                            "alpha=${child.alpha} hasBackground=${child.background != null}"
                    )
                }
            }
            shadeWindow.addView(
                lyricView,
                insertIndex,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            HookLogger.i(TAG, "全屏锁屏歌词视图已注入锁屏窗口: insertIndex=$insertIndex")
        } catch (error: Throwable) {
            synchronized(shadeWindows) { shadeWindows.remove(shadeWindow) }
            HookLogger.e(TAG, "注入全屏锁屏歌词视图失败", error)
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) refresh()
        else mainHandler.post { refresh() }
    }

    fun isFeatureEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_KEYGUARD_FULL_SCREEN_LYRICS_ENABLED,
        RootConstants.DEFAULT_HOOK_KEYGUARD_FULL_SCREEN_LYRICS_ENABLED
    ) ?: RootConstants.DEFAULT_HOOK_KEYGUARD_FULL_SCREEN_LYRICS_ENABLED

    /** 媒体卡片顶部（屏幕坐标）上报，用于划定歌词绘制下界。 */
    fun updateMediaCardTop(topOnScreen: Int) {
        mediaCardTopOnScreen = topOnScreen
    }

    fun refresh() = runOnMain {
        if (shadeWindows.isEmpty()) return@runOnMain
        val enabled = isFeatureEnabled()
        val context = shadeWindows.firstOrNull()?.context
        val keyguardLocked = context
            ?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        // 仅亮屏锁屏场景显示；熄屏 AOD 交给锁屏 AOD 歌词，且避免熄屏下持续重绘耗电
        val interactive = context
            ?.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        val song = LyriconDataBridge.currentSong
        val shouldShow = enabled && keyguardLocked && interactive &&
            !LyriconDataBridge.isTextMode
        val lyrics = song?.lyrics.orEmpty().filterNot { line ->
            line.metadata?.getBoolean(SongPreprocessor.KEY_TITLE_LINE) == true
        }
        HookLogger.i(
            TAG,
            "refresh: enabled=$enabled, keyguard=$keyguardLocked, interactive=$interactive, " +
                "textMode=${LyriconDataBridge.isTextMode}, song=${song?.name.orEmpty()}, " +
                "lyrics=${lyrics.size}, windows=${synchronized(shadeWindows) { shadeWindows.size }}, " +
                "mediaTop=$mediaCardTopOnScreen"
        )
        synchronized(shadeWindows) { shadeWindows.toList() }.forEach { shadeWindow ->
            val lyricView = findLyricView(shadeWindow)
            if (lyricView == null) {
                if (shouldShow && lyrics.isNotEmpty()) ensureLyricLayer(shadeWindow)
                return@forEach
            }
            if (!shouldShow || lyrics.isEmpty()) {
                if (lyricView.visibility != View.GONE) {
                    lyricView.visibility = View.GONE
                    lyricView.release()
                }
                return@forEach
            }
            bindBounds(shadeWindow, lyricView)
            applyTextStyle(lyricView)
            lyricView.setData(lyrics)
            lyricView.setCurrentIndex(resolveIndex(lyrics))
            if (lyricView.visibility != View.VISIBLE) {
                lyricView.visibility = View.VISIBLE
            }
        }
    }

    fun hide() = runOnMain {
        synchronized(shadeWindows) { shadeWindows.toList() }.forEach { shadeWindow ->
            findLyricView(shadeWindow)?.let { view ->
                view.visibility = View.GONE
                view.release()
            }
        }
    }

    fun releaseAll() = runOnMain {
        synchronized(shadeWindows) { shadeWindows.toList() }.forEach { shadeWindow ->
            findLyricView(shadeWindow)?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.release()
            }
        }
        shadeWindows.clear()
    }

    private fun findLyricView(shadeWindow: View): KeyguardFullScreenLyricView? {
        if (shadeWindow !is ViewGroup) return null
        for (index in 0 until shadeWindow.childCount) {
            val child = shadeWindow.getChildAt(index)
            if (child is KeyguardFullScreenLyricView) return child
        }
        return null
    }

    /** 计算绘制区间：时钟底部 ↔ 媒体卡片顶部（换算到锁屏窗口坐标系）。 */
    private fun bindBounds(shadeWindow: View, lyricView: KeyguardFullScreenLyricView) {
        val shadeLocation = IntArray(2)
        shadeWindow.getLocationOnScreen(shadeLocation)
        val statusView = runCatching {
            val id = shadeWindow.resources.getIdentifier(
                STATUS_VIEW_ID, "id", shadeWindow.context.packageName
            )
            if (id != 0) shadeWindow.findViewById<View>(id) else null
        }.getOrNull()
        val topOnScreen = statusView?.let { view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            location[1] + view.height
        } ?: (shadeLocation[1] + shadeWindow.height / 5)
        val bottomOnScreen = mediaCardTopOnScreen
            .takeIf { it > topOnScreen }
            ?: (shadeLocation[1] + shadeWindow.height * 3 / 4)
        lyricView.setDrawBounds(
            (topOnScreen - shadeLocation[1]).toFloat() + 4f * lyricView.resources.displayMetrics.density,
            (bottomOnScreen - shadeLocation[1]).toFloat() - 10f * lyricView.resources.displayMetrics.density,
        )
    }

    /** 读取「锁屏歌词配置」中的主句/翻译字号并应用到歌词视图。 */
    private fun applyTextStyle(lyricView: KeyguardFullScreenLyricView) {
        val preferences = prefs ?: return
        val mainSize = preferences.getFloat(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE,
            preferences.getInt(
                RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE
            ).toFloat()
        )
        val translationSize = preferences.getFloat(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE,
            preferences.getInt(
                RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE
            ).toFloat()
        )
        lyricView.setStyle(mainSize, translationSize)
    }

    /** 按播放进度定位当前行索引。 */
    private fun resolveIndex(lyrics: List<IRichLyricLine>): Int {
        if (lyrics.isEmpty()) return -1
        val position = LyriconDataBridge.estimatedPosition()
            ?: LyriconDataBridge.currentPosition
        var index = -1
        for (i in lyrics.indices) {
            if (lyrics[i].begin <= position) index = i else break
        }
        return index
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
