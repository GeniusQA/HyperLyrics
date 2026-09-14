package com.genius.hyperlyrics.root.mediacard.island

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.common.lyric.LyricMetadataKeys
import com.genius.hyperlyrics.root.HookEntry
import com.genius.hyperlyrics.root.LyriconDataBridge
import com.genius.hyperlyrics.root.mediacard.LyricGestureHelper
import com.genius.hyperlyrics.root.utils.CoverColorHelper
import com.genius.hyperlyrics.root.utils.OverlayFontColorApplier
import com.genius.hyperlyrics.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 超级岛展开大岛歌词：在媒体大岛卡片内、进度条下方展示歌词与翻译。
 *
 * - 复用 [IslandExpandedMediaAmbientFlowHooker] 的 binder 生命周期（attach/bind/detach）
 *   与展开可见性（DynamicIslandExpandedView.onVisibilityChanged）。
 * - 大岛卡片高度由系统 `DynamicIslandBaseContentView.getExpandedViewHeight()` 决定：
 *   hook 该方法按歌词实际高度叠加增量，同时同步撑高 holder.player，收起/关闭时完全还原。
 */
object IslandExpandedLyricHooker {
    private const val TAG = "IslandExpandedLyricHooker"
    private const val BINDER_CLASS =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl"
    private const val HOLDER_CLASS =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder"
    private const val MEDIA_DATA_CLASS =
        "com.android.systemui.media.controls.shared.model.MediaData"
    private const val BASE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    private const val EXPANDED_VIEW_CLASS =
        "miui.systemui.dynamicisland.view.DynamicIslandExpandedView"
    private const val OVERLAY_TAG = "hyperlyrics_island_expanded_lyrics"
    private const val LYRIC_TOP_GAP_DP = 6f
    private const val LYRIC_BOTTOM_GAP_DP = 10f
    // 移植包新架构的展开态信号：媒体背景视图播放启动（无 miui.systemui.dynamicisland 体系时）
    private const val MUSIC_BG_VIEW_CLASS = "com.mi.widget.view.MusicBgView"
    // 移植包岛窗口可见性切换较慢（实测 attach 后约 1.5~3s 才 VISIBLE），刷新窗口需覆盖到 6s
    private val INITIAL_REFRESH_DELAYS_MS =
        longArrayOf(0L, 150L, 400L, 800L, 1_500L, 2_500L, 4_000L, 6_000L)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val binderStates = Collections.synchronizedMap(
        WeakHashMap<Any, BinderLyricState>()
    )
    // 已注册展开监听的播放器视图
    private val watchedPlayers = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    // expandedView -> 歌词附加高度（px），供 getExpandedViewHeight Hook 叠加
    private val heightDeltas = Collections.synchronizedMap(
        WeakHashMap<View, Int>()
    )

    @Volatile
    private var module: XposedModule? = null

    private val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    private class BinderLyricState(
        val overlay: LinearLayout,
        val main: TextView,
        val translation: TextView,
        val backing: TextView,
        val backingTranslation: TextView,
        val player: ViewGroup,
        val expandedView: View,
        val seekBar: View?,
        val lyricParams: ViewGroup.MarginLayoutParams?,
        /** true=歌词靠 topMargin 挂在进度条下方；false=FrameLayout 贴底，用 bottomMargin 定位。 */
        val centerByTopMargin: Boolean,
        var playerBaseHeight: Int = -1,
        var appliedDelta: Int = 0,
        var appliedCenterExtra: Int = -1,
    )

    fun initialize(xposedModule: XposedModule) {
        module = xposedModule
    }

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        initialize(xposedModule)
        if (!hookedClassLoaders.add(classLoader)) return
        val api = resolveApi(classLoader) ?: run {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "跳过大岛歌词 Hook: reason=native_api_unavailable")
            return
        }
        var installed = 0
        api.hookMethods.forEach { method ->
            runCatching {
                xposedModule.deoptimize(method)
                val hooker = when (method.declaringClass.name) {
                    BINDER_CLASS -> when (method.name) {
                        "attach", "bindMediaData" -> BinderLifecycleHook()
                        "detach" -> BinderDetachHook()
                        else -> return@runCatching
                    }
                    BASE_CONTENT_VIEW_CLASS -> ExpandedHeightHook()
                    EXPANDED_VIEW_CLASS -> ExpandedVisibilityHook()
                    MUSIC_BG_VIEW_CLASS -> MusicBgViewHook()
                    else -> return@runCatching
                }
                xposedModule.hook(method).intercept(hooker)
                installed++
            }.onFailure {
                HookLogger.e(TAG, "安装大岛歌词 Hook 失败: method=${method.name}", it)
            }
        }
        HookLogger.i(TAG, "大岛歌词 Hook 已初始化: methods=$installed")
    }

    /**
     * 展开信号监听：移植包的 DynamicIslandExpandedView 在插件 classLoader 里，
     * 主加载器 hook 不到 onVisibilityChanged。改在播放器视图上注册 attach/布局监听——
     * 大岛展开时播放器必然 attach 到窗口且尺寸从摘要态变为大岛态，以此触发刷新。
     */
    private fun watchPlayerExpansion(player: ViewGroup) {
        if (!watchedPlayers.add(player)) return
        player.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                HookLogger.d(TAG, "大岛播放器 attach，调度展开刷新")
                scheduleExpandRefresh()
            }

            override fun onViewDetachedFromWindow(v: View) {
                // 收起/关闭时立即隐藏，避免残留
                runOnMain {
                    synchronized(binderStates) { binderStates.values.toList() }
                        .forEach(::hideOverlay)
                }
            }
        })
        player.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (l != ol || t != ot || r != orr || b != ob) {
                scheduleExpandRefresh()
            }
        }
    }

    private fun scheduleExpandRefresh() {
        INITIAL_REFRESH_DELAYS_MS.forEach { delay ->
            mainHandler.postDelayed({ refresh() }, delay)
        }
    }

    fun refresh() = runOnMain {
        synchronized(binderStates) { binderStates.keys.toList() }.forEach { binder ->
            runCatching { applyState(binder) }
                .onFailure { HookLogger.e(TAG, "刷新大岛歌词失败", it) }
        }
    }

    fun onPlaybackStateChanged() = refresh()

    fun releaseAll() = runOnMain {
        synchronized(binderStates) { binderStates.keys.toList() }.forEach(::cleanupBinder)
        binderStates.clear()
        synchronized(heightDeltas) { heightDeltas.keys.toList() }.forEach(heightDeltas::remove)
    }

    private class BinderLifecycleHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val binder = chain.thisObject ?: return chain.proceed()
            val result = chain.proceed()
            runOnMain {
                runCatching { applyState(binder) }
                    .onFailure { HookLogger.e(TAG, "binder 回调应用大岛歌词失败", it) }
            }
            INITIAL_REFRESH_DELAYS_MS.drop(1).forEach { delay ->
                mainHandler.postDelayed({
                    runCatching {
                        if (binderStates.containsKey(binder) || isEnabled()) {
                            applyState(binder)
                        }
                    }
                }, delay)
            }
            return result
        }
    }

    private class BinderDetachHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val binder = chain.thisObject
            val result = chain.proceed()
            binder?.let {
                runOnMain { cleanupBinder(it) }
            }
            return result
        }
    }

    /** 展开大岛高度叠加：系统读取高度时追加歌词增量。 */
    private class ExpandedHeightHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val contentView = chain.thisObject ?: return result
            val base = (result as? Number)?.toInt() ?: return result
            val expandedView = findContentViewExpandedView(contentView) ?: return result
            val delta = synchronized(heightDeltas) { heightDeltas[expandedView] } ?: 0
            return if (delta > 0) base + delta else result
        }
    }

    /**
     * 大岛展开/收起监听：bind 回调发生时大岛往往尚未展开（isShown=false），
     * 必须在展开可见时重新应用歌词；收起时隐藏并还原高度。
     */
    private class ExpandedVisibilityHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val visibility = (chain.args.getOrNull(1) as? Number)?.toInt()
            if (visibility == View.VISIBLE) {
                INITIAL_REFRESH_DELAYS_MS.forEach { delay ->
                    mainHandler.postDelayed({ refresh() }, delay)
                }
            } else {
                runOnMain {
                    synchronized(binderStates) { binderStates.values.toList() }
                        .forEach(::hideOverlay)
                }
            }
            return result
        }
    }

    /**
     * 移植包新架构降级信号：媒体背景视图 start/resume 发生在展开态媒体卡片出现时。
     * applyState 内部有 isShown 门控，摘要态误触发不会显示歌词。
     */
    private class MusicBgViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            INITIAL_REFRESH_DELAYS_MS.forEach { delay ->
                mainHandler.postDelayed({ refresh() }, delay)
            }
            return result
        }
    }

    private class IslandApi private constructor(
        val hookMethods: List<Method>,
        private val holderField: Field,
        private val dummyHolderField: Field,
        private val playerField: Field,
        private val titleTextField: Field,
        private val artistTextField: Field,
        private val seekBarField: Field,
        private val mediaDataField: Field,
        private val mediaDataIsPlayingField: Field,
        private val mediaDataPackageNameField: Field,
    ) {
        fun getHolders(binder: Any): List<Any> =
            listOfNotNull(holderField.get(binder), dummyHolderField.get(binder)).distinct()

        fun getPlayer(holder: Any): View = playerField.get(holder) as View

        fun getTitleText(holder: Any): TextView = titleTextField.get(holder) as TextView

        fun getArtistText(holder: Any): TextView = artistTextField.get(holder) as TextView

        fun getSeekBar(holder: Any): View = seekBarField.get(holder) as View

        fun isPlaying(binder: Any): Boolean {
            val mediaData = mediaDataField.get(binder) ?: return false
            return mediaDataIsPlayingField.get(mediaData) == true
        }

        fun getPackageName(binder: Any): String? {
            val mediaData = mediaDataField.get(binder) ?: return null
            return mediaDataPackageNameField.get(mediaData) as? String
        }

        companion object {
            fun create(classLoader: ClassLoader): IslandApi {
                val binderClass = classLoader.loadClass(BINDER_CLASS)
                val holderClass = classLoader.loadClass(HOLDER_CLASS)
                val mediaDataClass = classLoader.loadClass(MEDIA_DATA_CLASS)
                // 高度方法与展开可见性方法在部分版本/移植包可能缺失，容错降级为不撑高/不监听展开。
                val heightMethod = runCatching {
                    classLoader.loadClass(BASE_CONTENT_VIEW_CLASS).methods.firstOrNull {
                        it.name == "getExpandedViewHeight" && it.parameterTypes.isEmpty()
                    }
                }.getOrNull()?.apply { isAccessible = true }
                val visibilityMethod = runCatching {
                    classLoader.loadClass(EXPANDED_VIEW_CLASS).declaredMethods.firstOrNull {
                        it.name == "onVisibilityChanged" && it.parameterCount == 2
                    }?.apply { isAccessible = true }
                }.getOrNull()
                val musicBgMethods = runCatching {
                    classLoader.loadClass(MUSIC_BG_VIEW_CLASS).declaredMethods.filter {
                        (it.name == "start" || it.name == "resume") && it.parameterCount == 0
                    }.map { it.apply { isAccessible = true } }
                }.getOrDefault(emptyList())
                return IslandApi(
                    hookMethods = listOfNotNull(
                        binderClass.declaredMethods.single {
                            it.name == "attach" && it.parameterCount == 2
                        }.apply { isAccessible = true },
                        binderClass.declaredMethods.single {
                            it.name == "bindMediaData" && it.parameterCount == 1
                        }.apply { isAccessible = true },
                        binderClass.declaredMethods.single {
                            it.name == "detach" && it.parameterCount == 0
                        }.apply { isAccessible = true },
                        heightMethod,
                        visibilityMethod,
                    ) + musicBgMethods,
                    holderField = binderClass.getDeclaredField("holder").apply {
                        isAccessible = true
                    },
                    dummyHolderField = binderClass.getDeclaredField("dummyHolder").apply {
                        isAccessible = true
                    },
                    playerField = holderClass.getDeclaredField("player").apply {
                        isAccessible = true
                    },
                    titleTextField = holderClass.getDeclaredField("titleText").apply {
                        isAccessible = true
                    },
                    artistTextField = holderClass.getDeclaredField("artistText").apply {
                        isAccessible = true
                    },
                    seekBarField = holderClass.getDeclaredField("seekBar").apply {
                        isAccessible = true
                    },
                    mediaDataField = binderClass.getDeclaredField("mediaData").apply {
                        isAccessible = true
                    },
                    mediaDataIsPlayingField = mediaDataClass.getDeclaredField("isPlaying").apply {
                        isAccessible = true
                    },
                    mediaDataPackageNameField =
                        mediaDataClass.getDeclaredField("packageName").apply {
                            isAccessible = true
                        },
                )
            }
        }
    }

    @Volatile
    private var nativeApi: IslandApi? = null

    private fun resolveApi(classLoader: ClassLoader): IslandApi? {
        nativeApi?.let { return it }
        return runCatching { IslandApi.create(classLoader) }
            .onSuccess { nativeApi = it }
            .onFailure { HookLogger.w(TAG, "大岛歌词接口不可用: reason=${it.message}") }
            .getOrNull()
    }

    private fun isEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_LYRICS_ENABLED,
        RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_LYRICS_ENABLED
    ) ?: RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_LYRICS_ENABLED

    /** 大岛独立字号（主句），默认与锁屏AOD默认一致。 */
    private fun mainTextSize(): Int = prefs?.getInt(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_MAIN_TEXT_SIZE,
        RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE
    ) ?: RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE

    private fun backingTextSize(): Int = prefs?.getInt(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_BACKING_TEXT_SIZE,
        RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE
    ) ?: RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE

    private fun translationTextSize(): Int = prefs?.getInt(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_TRANSLATION_TEXT_SIZE,
        RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE
    ) ?: RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE

    private fun applyState(binder: Any) {
        val api = nativeApi ?: return
        if (!isEnabled()) {
            cleanupBinder(binder)
            return
        }
        val playing = LyriconDataBridge.currentPlaybackState
            ?: runCatching { api.isPlaying(binder) }.getOrNull() ?: false
        val lyricPackage = LyriconDataBridge.currentLyricPackageName
        val mediaPackage = runCatching { api.getPackageName(binder) }.getOrNull()
        val packageMatches = lyricPackage.isNullOrBlank() ||
            mediaPackage.isNullOrBlank() || lyricPackage == mediaPackage
        val line = LyriconDataBridge.currentLyricLine
        val main = line?.text?.trim().orEmpty().ifBlank {
            LyriconDataBridge.currentLyric?.trim().orEmpty()
        }
        val translation = line?.translation?.trim().orEmpty()
            .takeUnless { it == main }
            .orEmpty()
        val backing = line?.secondary?.trim().orEmpty()
            .takeUnless { it == main }
            .orEmpty()
        val backingTranslation = line?.metadata
            ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
            ?.trim().orEmpty()
            .takeUnless { backing.isBlank() || it == backing }
            .orEmpty()
        val hasLyric = main.isNotBlank()
        val show = playing && hasLyric && packageMatches

        // binder 同时持有真 holder 与 dummyHolder，dummyHolder 的播放器不可见；
        // 必须按「任一 holder 展开可见」统一决策，且只在可见的播放器上挂载，
        // 否则 dummyHolder 的隐藏判定会与真 holder 的显示交替振荡（表现为歌词被置 GONE）。
        val holderEntries = api.getHolders(binder).mapNotNull { holder ->
            val player = runCatching { api.getPlayer(holder) as? ViewGroup }
                .getOrNull() ?: return@mapNotNull null
            val expandedView = findExpandedView(player) ?: return@mapNotNull null
            logStructureIfNeeded(player, expandedView)
            watchPlayerExpansion(player)
            val title = runCatching { api.getTitleText(holder) }.getOrNull()
                ?: return@mapNotNull null
            HolderEntry(holder, player, expandedView, title)
        }
        val visibleEntry = holderEntries.firstOrNull { it.expandedView.isShown }
        val state = synchronized(binderStates) { binderStates[binder] }
        if (!show || visibleEntry == null) {
            state?.let(::hideOverlay)
            return
        }
        val entry = visibleEntry
        val artist = runCatching { api.getArtistText(entry.holder) }.getOrNull()
        val lyricState = state
            ?: createOverlay(api, binder, entry.holder, entry.player, entry.expandedView, entry.title, artist)
        if (lyricState == null) return
        lyricState.main.text = main
        setOptionalText(lyricState.translation, translation)
        setOptionalText(lyricState.backing, backing)
        setOptionalText(lyricState.backingTranslation, backingTranslation)
        lyricState.main.setTextColor(entry.title.currentTextColor)
        lyricState.backing.setTextColor(entry.title.currentTextColor)
        val translationColor = artist?.currentTextColor ?: entry.title.currentTextColor
        lyricState.translation.setTextColor(translationColor)
        lyricState.backingTranslation.setTextColor(translationColor)
        // 字体颜色设置（莫奈取色/封面色/封面渐变色/自定义）非默认时覆盖系统跟随色。
        // 大岛卡片暂无稳定封面 bitmap 来源，封面色/渐变模式回退默认白色。
        OverlayFontColorApplier.apply(
            prefs = prefs,
            res = entry.player.resources,
            keys = RootConstants.FONT_COLOR_KEYS_ISLAND_EXPANDED,
            targets = listOf(lyricState.main, lyricState.backing),
            secondaryTargets = listOf(lyricState.translation, lyricState.backingTranslation),
            albumBitmap = CoverColorHelper.currentArtwork(),
            mediaColorKey = CoverColorHelper.currentMediaKey(),
        )
        if (lyricState.overlay.visibility != View.VISIBLE) {
            lyricState.overlay.visibility = View.VISIBLE
        }
        updateExpandedHeight(lyricState)
    }

    private class HolderEntry(
        val holder: Any,
        val player: ViewGroup,
        val expandedView: View,
        val title: TextView,
    )

    private fun createOverlay(
        api: IslandApi,
        binder: Any,
        holder: Any,
        player: ViewGroup,
        expandedView: View,
        title: TextView,
        artist: TextView?,
    ): BinderLyricState? {
        val context = player.context
        val density = context.resources.displayMetrics.density
        fun lyricText(sizeSp: Int, color: Int, typeface: android.graphics.Typeface?) =
            TextView(context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                this.typeface = typeface
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
                setTextColor(color)
                setSingleLine(false)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        val main = lyricText(
            mainTextSize(),
            title.currentTextColor,
            title.typeface,
        )
        val translation = lyricText(
            translationTextSize(),
            artist?.currentTextColor ?: title.currentTextColor,
            artist?.typeface ?: title.typeface,
        )
        val backing = lyricText(
            backingTextSize(),
            title.currentTextColor,
            title.typeface,
        )
        val backingTranslation = lyricText(
            translationTextSize(),
            artist?.currentTextColor ?: title.currentTextColor,
            artist?.typeface ?: title.typeface,
        )
        val root = LinearLayout(context).apply {
            tag = OVERLAY_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            visibility = View.INVISIBLE
            addView(main, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(translation, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4f * density).toInt() })
            addView(backing, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4f * density).toInt() })
            addView(backingTranslation, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4f * density).toInt() })
        }
        val seekBar = runCatching { api.getSeekBar(holder) }.getOrNull()
        val topGap = (LYRIC_TOP_GAP_DP * density).toInt()
        val useConstraintParams = player.javaClass.name.contains("ConstraintLayout")
        val lp = runCatching {
            if (useConstraintParams) {
                val loader = requireNotNull(player.javaClass.classLoader)
                val paramsClass = loader.loadClass(
                    "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"
                )
                val params = paramsClass.getConstructor(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).newInstance(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ) as ViewGroup.MarginLayoutParams
                paramsClass.getField("topToBottom").setInt(
                    params,
                    seekBar?.id?.takeIf { it != View.NO_ID } ?: View.NO_ID,
                )
                paramsClass.getField("startToStart").setInt(params, 0)
                paramsClass.getField("endToEnd").setInt(params, 0)
                params.topMargin = topGap
                params
            } else {
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM
                    bottomMargin = (LYRIC_BOTTOM_GAP_DP * density).toInt()
                }
            }
        }.getOrNull() ?: return null
        val baseHeight = player.layoutParams?.height ?: -1
        player.addView(root, lp)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(
                player.width.takeIf { it > 0 } ?: player.measuredWidth,
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val state = BinderLyricState(
            overlay = root,
            main = main,
            translation = translation,
            backing = backing,
            backingTranslation = backingTranslation,
            player = player,
            expandedView = expandedView,
            seekBar = seekBar,
            lyricParams = lp,
            centerByTopMargin = useConstraintParams,
            playerBaseHeight = baseHeight,
        )
        synchronized(binderStates) { binderStates[binder] = state }
        // 大岛歌词行接管播放控制手势（长按=播放/暂停，双击左/右半区=上/下一首）。
        LyricGestureHelper.attach(main, translation)
        HookLogger.i(
            TAG,
            "大岛歌词覆盖层已挂载: player=${player.javaClass.name}, " +
                "baseHeight=$baseHeight, expanded=${expandedView.javaClass.name}"
        )
        return state
    }

    private fun updateExpandedHeight(state: BinderLyricState) {
        val density = state.player.resources.displayMetrics.density
        // 每次都按当前文本重新测量：多行歌词/翻译会改变内容高度，
        // 沿用上一次的 measuredHeight 会让定位滞后一拍，出现歌词与进度条互相挤压。
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            (state.player.width.takeIf { it > 0 } ?: state.player.measuredWidth)
                .coerceAtLeast(1),
            View.MeasureSpec.EXACTLY
        )
        state.overlay.measure(
            widthSpec,
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val measuredHeight = state.overlay.measuredHeight
        if (measuredHeight <= 0) return
        val topGap = (LYRIC_TOP_GAP_DP * density).toInt()
        val bottomGap = (LYRIC_BOTTOM_GAP_DP * density).toInt()
        val delta = measuredHeight + topGap + bottomGap
        // 歌词块在「进度条下缘 ~ 卡片底部」的剩余空间内垂直居中：
        // 撑高量保持不变，只把歌词整体下移剩余空间的一半；
        // 多行折行与翻译都在同一个纵向容器内，只会把容器撑高，不会出现行间重叠。
        val extra = centeredOffsetExtra(state)
        if (state.appliedDelta != delta) {
            synchronized(heightDeltas) { heightDeltas[state.expandedView] = delta }
            state.appliedDelta = delta
            if (state.playerBaseHeight > 0) {
                state.player.layoutParams?.let { params ->
                    params.height = state.playerBaseHeight + delta
                    state.player.layoutParams = params
                }
            }
            state.expandedView.requestLayout()
            HookLogger.i(TAG, "大岛歌词撑高: delta=$delta")
        }
        if (state.appliedCenterExtra != extra) {
            state.appliedCenterExtra = extra
            state.lyricParams?.let { params ->
                if (state.centerByTopMargin) {
                    params.topMargin = topGap + extra
                } else {
                    params.bottomMargin = (bottomGap - extra).coerceAtLeast(0)
                }
                state.overlay.layoutParams = params
            }
        }
    }

    /**
     * 歌词块居中所需的额外下移量：取「原生卡片中进度条下缘到卡片底部」剩余空间的一半。
     * 进度条缺失或尚未布局时返回 0，保持原有紧贴进度条下方的行为。
     */
    private fun centeredOffsetExtra(state: BinderLyricState): Int {
        val seekBar = state.seekBar ?: return 0
        if (!seekBar.isAttachedToWindow || !state.player.isAttachedToWindow) return 0
        val base = state.playerBaseHeight
        if (base <= 0) return 0
        return runCatching {
            val seekBarLocation = IntArray(2)
            val playerLocation = IntArray(2)
            seekBar.getLocationInWindow(seekBarLocation)
            state.player.getLocationInWindow(playerLocation)
            val seekBarBottom = seekBarLocation[1] - playerLocation[1] + seekBar.height
            ((base - seekBarBottom) / 2).coerceIn(0, base)
        }.getOrDefault(0)
    }

    private fun hideOverlay(state: BinderLyricState) {
        if (state.overlay.visibility != View.GONE) {
            state.overlay.visibility = View.GONE
        }
        if (state.appliedDelta != 0) {
            synchronized(heightDeltas) { heightDeltas.remove(state.expandedView) }
            if (state.playerBaseHeight > 0) {
                state.player.layoutParams?.let { params ->
                    params.height = state.playerBaseHeight
                    state.player.layoutParams = params
                }
            }
            state.expandedView.requestLayout()
            state.appliedDelta = 0
        }
    }

    private fun cleanupBinder(binder: Any) {
        synchronized(binderStates) { binderStates.remove(binder) }?.let { state ->
            runCatching {
                (state.overlay.parent as? ViewGroup)?.removeView(state.overlay)
                hideOverlay(state)
            }
        }
    }

    @Volatile
    private var structureLogged = false

    /** 首次拿到播放器视图时打印结构，便于真机适配诊断（只打一次）。 */
    private fun logStructureIfNeeded(player: ViewGroup, expandedView: View) {
        if (structureLogged) return
        structureLogged = true
        val hierarchy = generateSequence<Class<*>>(player.javaClass) { it.superclass }
            .take(4)
            .joinToString(" -> ") { it.name.substringAfterLast('.') }
        HookLogger.i(
            TAG,
            "大岛视图结构: player=$hierarchy, lp=${player.layoutParams?.javaClass?.name}, " +
                "height=${player.layoutParams?.height}, measured=${player.measuredWidth}x${player.measuredHeight}, " +
                "expanded=${expandedView.javaClass.name}, shown=${expandedView.isShown}"
        )
    }

    private fun findExpandedView(from: View): View? {
        var current: View? = from
        for (i in 0 until 16) {
            val parent = current?.parent as? View ?: return from
            if (parent.javaClass.name == EXPANDED_VIEW_CLASS) return parent
            current = parent
        }
        // 移植包新架构没有 DynamicIslandExpandedView，用播放器自身做展开视图代理
        // （展开时 isShown=true，摘要/收起时为 false，语义等价）。
        return from
    }

    private fun findContentViewExpandedView(contentView: Any): View? {
        return runCatching {
            contentView.javaClass.methods.firstOrNull {
                it.name == "getExpandedView" && it.parameterTypes.isEmpty()
            }?.invoke(contentView) as? View
        }.getOrNull()
    }

    private fun setOptionalText(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
