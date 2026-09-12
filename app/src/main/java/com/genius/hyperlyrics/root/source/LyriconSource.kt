package com.genius.hyperlyrics.root.source

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.genius.hyperlyrics.BuildConfig
import com.genius.hyperlyrics.R
import com.genius.hyperlyrics.common.IslandAlbumCoverWhitelist
import com.genius.hyperlyrics.common.PrefsBridge
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.common.lyric.AppleOriginalMetadataPolicy
import com.genius.hyperlyrics.common.lyric.AppleMissingLyricsSourceInfo
import com.genius.hyperlyrics.common.lyric.AppleMissingLyricsSourceMetadata
import com.genius.hyperlyrics.common.lyric.AppleMissingLyricsSourceStatus
import com.genius.hyperlyrics.common.lyric.ApplePronunciationVisibilityPolicy
import com.genius.hyperlyrics.common.lyric.ChineseLyricsPolicy
import com.genius.hyperlyrics.common.lyric.LyricMetadataKeys
import com.genius.hyperlyrics.common.lyric.OnlineTranslationContentPolicy
import com.genius.hyperlyrics.common.lyric.RomanizationPolicy
import com.genius.hyperlyrics.common.lyric.TraditionalLyricsSimplifier
import com.genius.hyperlyrics.common.media.MediaMetadataHelper
import com.genius.hyperlyrics.common.media.NextTrackMetadataCache
import com.genius.hyperlyrics.lyric.LrcLine
import com.genius.hyperlyrics.lyric.model.Song as LocalSong
import com.genius.hyperlyrics.lyric.model.lyricMetadataOf
import com.genius.hyperlyrics.lyric.source.LyricSink
import com.genius.hyperlyrics.lyric.source.LyricSource
import com.genius.hyperlyrics.online.OnlineLyricTargeter
import com.genius.hyperlyrics.online.OnlineTranslationSourcePreferences
import com.genius.hyperlyrics.online.model.ManualLyricMatchRequest
import com.genius.hyperlyrics.online.model.SongSearchResult
import com.genius.hyperlyrics.online.model.Source
import com.genius.hyperlyrics.online.source.lunabeat.LunaBeatLookupResult
import com.genius.hyperlyrics.online.source.lunabeat.LunaBeatTtmlRepository
import com.genius.hyperlyrics.online.utils.ChineseUtils
import com.genius.hyperlyrics.root.LyriconDataBridge
import com.genius.hyperlyrics.root.island.renderer.BaseIslandRenderer
import com.genius.hyperlyrics.provider.UniversalFallbackProvider
import com.genius.hyperlyrics.root.utils.HookLogger
import com.genius.hyperlyrics.root.utils.MediaCardDiagnosticLogger
import io.github.proify.lyricon.amprovider.xposed.AppleDirectBridgeContract
import io.github.proify.lyricon.amprovider.xposed.AppleSourceSwitchPerformanceDiagnostics
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 在线翻译匹配是否允许把 [song] 当作可用歌词基准。
 *
 * Apple 原生歌词得到确认后始终允许；否则只有已标记为「无歌词补充」且确实携带
 * 歌词的载荷才能进入匹配，避免把空歌词占位或普通兜底歌词误当成可匹配对象。
 */
internal fun hasAppleLyricsForOnlineEnrichment(
    song: LocalSong?,
    confirmedNativeLyrics: Boolean,
): Boolean = confirmedNativeLyrics || (
    song != null &&
        song.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
            .toBoolean() &&
        !song.lyrics.isNullOrEmpty()
    )

/**
 * Automatic fallback must not replace confirmed Apple-native lyrics. A user-initiated
 * source switch is different: the request explicitly asks for a third-party supplement,
 * even if an intervening Apple callback temporarily marks the same track as native again.
 */
internal fun acceptsAppleOnlineLyricResult(
    generation: Int,
    currentGeneration: Int,
    sameTrack: Boolean,
    currentNativeLyrics: Boolean,
    currentSongHasNativeLyrics: Boolean,
    manualSourceSwitch: Boolean,
    automaticLunaBeatOverride: Boolean = false,
): Boolean = generation == currentGeneration &&
    sameTrack &&
    (!(currentNativeLyrics || currentSongHasNativeLyrics) ||
        manualSourceSwitch ||
        automaticLunaBeatOverride)

internal fun hasConfirmedAppleNativeLyrics(song: LocalSong?): Boolean =
    song != null &&
        !song.lyrics.isNullOrEmpty() &&
        !song.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
            .toBoolean() &&
        song.metadata
            ?.getString(LyricMetadataKeys.APPLE_NATIVE_LYRICS_CONFIRMED)
            .toBoolean()

internal fun shouldKeepRunningAppleEnrichment(
    sameTrack: Boolean,
    authoritativeNativeTransition: Boolean,
    hasLyrics: Boolean,
    needsEnrichment: Boolean,
    originalMetadataChanged: Boolean,
    enrichmentRunning: Boolean,
): Boolean = sameTrack &&
    !authoritativeNativeTransition &&
    hasLyrics &&
    needsEnrichment &&
    !originalMetadataChanged &&
    enrichmentRunning

internal fun shouldClearAppleOnlineTranslationAttempt(
    sameTrack: Boolean,
    authoritativeNativeTransition: Boolean,
    needsEnrichment: Boolean,
    originalMetadataChanged: Boolean,
): Boolean = authoritativeNativeTransition ||
    !sameTrack ||
    !needsEnrichment ||
    originalMetadataChanged

/**
 * Central Apple callbacks can rebuild the same lyric model from TTML and drop the
 * process-independent supplement metadata. Keep the already confirmed supplement
 * marker and source description attached to that same track so it is not promoted
 * back to an Apple-native song before source recovery completes.
 */
internal fun mergeMissingLyricsSupplementMetadata(
    previousSong: LocalSong?,
    incomingSong: LocalSong?,
    sameTrack: Boolean,
    authoritativeSource: String? = null,
): LocalSong? {
    if (!sameTrack || previousSong == null || incomingSong == null) return incomingSong
    val previousMetadata = previousSong.metadata ?: return incomingSong
    val previousSource = previousMetadata
        .getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
    if (hasConfirmedAppleNativeLyrics(incomingSong)) {
        return if (
            authoritativeSource == Source.LB.name &&
            previousSource == Source.LB.name &&
            previousMetadata
                .getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
                .toBoolean()
        ) {
            previousSong
        } else {
            incomingSong
        }
    }
    if (!previousMetadata.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT).toBoolean()) {
        return incomingSong
    }
    val incomingIsSupplement = incomingSong.metadata
        ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
        .toBoolean()
    val incomingSource = incomingSong.metadata
        ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
    if (
        incomingIsSupplement &&
        !authoritativeSource.isNullOrBlank() &&
        previousSource == authoritativeSource &&
        !incomingSource.isNullOrBlank() &&
        incomingSource != authoritativeSource
    ) {
        // 手动切换成功后，旧来源的同曲异步回调不得把正文、时间轴和来源一起回滚。
        return previousSong
    }
    if (incomingIsSupplement && (
            authoritativeSource.isNullOrBlank() || incomingSource == authoritativeSource
        )
    ) {
        return incomingSong
    }
    val merged = linkedMapOf<String, String?>()
    incomingSong.metadata?.forEach { (key, value) -> merged[key] = value }
    merged[LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT] = "true"
    listOf(
        LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE,
        LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES,
        LyricMetadataKeys.LUNA_BEAT_RAW_TTML,
        LyricMetadataKeys.LUNA_BEAT_HUB_ID,
        LyricMetadataKeys.LUNA_BEAT_TTML_SHA256,
    ).forEach { key ->
        if (merged[key].isNullOrBlank()) {
            previousMetadata.getString(key)?.let { merged[key] = it }
        }
    }
    authoritativeSource?.takeIf(String::isNotBlank)?.let {
        merged[LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE] = it
    }
    return incomingSong.copy(
        metadata = lyricMetadataOf(*merged.entries.map { it.key to it.value }.toTypedArray())
    )
}

class LyriconSource : LyricSource {

    companion object {
        private const val TAG = "LyriconSource"
        private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        private const val BUILT_IN_PROVIDER_PACKAGE = RootConstants.BUILT_IN_LYRIC_PROVIDER_PACKAGE
        private const val UNIVERSAL_FALLBACK_PROVIDER_PACKAGE =
            RootConstants.UNIVERSAL_FALLBACK_LYRIC_PROVIDER_PACKAGE
        private const val APPLE_LYRICS_GRACE_MS = 5_000L
        private const val SALT_LOCAL_LYRICS_GRACE_MS = 3_000L
        private const val APPLE_MEDIA_MONITOR_INTERVAL_MS = 1_000L
        private const val SAME_TRACK_DURATION_TOLERANCE_MS = 2_000L
        private const val TIMING_DIAGNOSTIC_INTERVAL_MS = 5_000L
        private const val SOURCE_SWITCH_DIAGNOSTIC_WINDOW_MS = 20_000L
        private const val APPLE_NATIVE_LYRICS_SOURCE = "APPLE"
        private const val PRONUNCIATION_DIAGNOSTIC_TAG = "ApplePronunciationDiag"
        private const val ROMANIZATION_REPLACE_TIME_WINDOW_MS = 8_000L
        /** 本地媒体会话轮询间隔：兜底 MIUI 下 change 回调对其他 UID 会话不触发的情况。 */
        private const val LOCAL_SESSION_POLL_INTERVAL_MS = 3_000L
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }

    private fun LyriconSong.toLocalSong(): LocalSong {
        val jsonString = json.encodeToString(this)
        return json.decodeFromString(jsonString)
    }

    override val id = "lyricon"
    override val displayName = "Lyricon"

    @Volatile
    private var sink: LyricSink? = null
    private var app: Application? = null
    @Volatile
    private var subscriber: LyriconSubscriber? = null

    @Volatile
    private var activeProviderPackageName: String? = null
        set(value) {
            if (field != value) {
                field = value
                PrefsBridge.putString(
                    RootConstants.KEY_HOOK_CURRENT_LYRIC_PROVIDER,
                    value,
                )
            }
        }
    @Volatile
    private var activeCentralPlayerPackageName: String? = null
    private var activeProviderDelayMs: Int = RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
    private var prefs: android.content.SharedPreferences? = null
    private var onCentralConnected: (() -> Unit)? = null
    private var onCentralConnectTimeout: (() -> Unit)? = null
    private var directBridge: AppleMusicDirectBridge? = null
    private val loggedPlayerVersionSnapshots = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fallbackRequestMutex = Mutex()
    private val mediaPositionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fallbackJob: Job? = null
    private var onlineTranslationJob: Job? = null
    private var mediaPositionJob: Job? = null
    private var fallbackDelayRunnable: Runnable? = null
    private var fallbackGeneration = 0
    private var thirdPartyFallbackJob: Job? = null
    private var thirdPartyFallbackDelayRunnable: Runnable? = null
    private var thirdPartyFallbackGeneration = 0
    private var thirdPartyFallbackSongActive = false

    /** 当前曲目匹配失败后展示报错占位；同一首歌的重复占位回调不应覆盖报错。 */
    private var thirdPartyMatchFailedIdentity: String? = null
    private var onlineTranslationGeneration = 0
    private var onlineTranslationAttemptKey: String? = null
    private var originalMetadataRequestKey: String? = null
    private var onlineMatchedTranslationActive = false
    private var onlineRaceFirstPublishedGeneration: Int? = null
    private var onlineRaceFirstAcceptedGeneration: Int? = null
    private var pendingOnlineTranslationCommit: PendingOnlineTranslationCommit? = null
    private var temporaryTranslationSource: Source? = null
    private var temporaryPronunciationSource: Source? = null
    private var pendingTranslationSourceRequest: OnlineSourceSwitchRequest? = null
    private var pendingPronunciationSourceRequest: OnlineSourceSwitchRequest? = null
    private var pendingLyricsSourceRequest: OnlineSourceSwitchRequest? = null
    @Volatile
    private var latestSourceSwitchTraceRequest: OnlineSourceSwitchRequest? = null
    private var confirmedLyricsSourceSelection: ConfirmedLyricsSourceSelection? = null
    private var currentAppleSong: LocalSong? = null
    private var currentAppleNativeSong: LocalSong? = null
    private var currentAppleHasNativeLyrics = false
    private var currentPublishedAppleSong: LocalSong? = null
    private var currentThirdPartySong: LocalSong? = null
    private var currentPublishedThirdPartySong: LocalSong? = null
    private var currentPublishedAppleOnlineTranslationMatched = false
    @Volatile
    private var fallbackSongActive = false
    private var lastAdjustedPosition = 0L
    private var appleSongGeneration = 0
    @Volatile
    private var appleMediaPositionReference: AppleCentralPositionPolicy.MediaReference? = null
    @Volatile
    private var appleDirectPositionReference: AppleCentralPositionPolicy.DirectReference? = null
    @Volatile
    private var currentDirectAppleSongId: String? = null
    private var lastObservedMediaKey: String? = null
    private var lastMediaPlaybackState: Boolean? = null
    private val centralPlaybackPositionWitness = CentralPlaybackPositionWitness()
    private var audioManager: AudioManager? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var localSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var localSessionPollJob: Job? = null
    private var universalFallbackProvider: UniversalFallbackProvider? = null

    private val activeMediaSessionGate = ActiveMediaSessionGate(
        nowElapsedMs = SystemClock::elapsedRealtime,
        nowWallClockMs = System::currentTimeMillis,
        isMusicActive = ::isAnyMusicActive,
    )
    private var mediaSessionGateStopIssued = false

    /**
     * SystemUI 进程内的权威会话真值（AOD-LYRICS-004 `150213` 证据：app 侧
     * 监听器被 MIUI 解绑后会沉默，快照可冻结在过期值上）。
     *
     * 关键修复：注册监听时必须传入**已启用的通知监听服务组件**
     * （[com.genius.hyperlyrics.service.LiveLyricService]），而非 `null`。
     * `addOnActiveSessionsChangedListener(listener, null)` 的回调只会在
     * 调用方自身 UID 的会话变化时触发，而 HyperLyrics 在 SystemUI 进程里，
     * 酷狗等播放器是其他 UID——导致：
     * - 系统启动 / 播放器早于模块加载时，初始 `getActiveSessions` 若为空，
     *   之后再无 change 事件，歌词永久不显示；
     * - 中途偶发能显示，只是被其他路径的快照补到。
     * 传入通知监听组件后，change 回调对全部会话生效；再辅以周期轮询兜底，
     * 彻底消除“重启后不展示歌词”。
     */
    private fun localMediaSessionListenerComponent(): ComponentName? =
        runCatching {
            ComponentName(
                "com.genius.hyperlyrics",
                "com.genius.hyperlyrics.service.LiveLyricService",
            )
        }.getOrNull()

    /**
     * 读取当前活动会话；优先用通知监听组件（可拿到全部 UID 的会话），
     * 若该组件未启用而抛异常，则回退 [MediaSessionManager.getActiveSessions] 的
     * null 形式（SystemUI 系统 UID 下仍可读到全部会话）。
     */
    private fun readActiveSessions(
        manager: MediaSessionManager,
        component: ComponentName?,
    ): List<MediaController> =
        runCatching { manager.getActiveSessions(component) }
            .getOrElse { runCatching { manager.getActiveSessions(null) }.getOrElse { emptyList() } }

    private fun registerLocalMediaSessionTracker() {
        val context = app ?: return
        if (localSessionsListener != null) return
        runCatching {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = localMediaSessionListenerComponent()
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                onLocalActiveMediaSessionsChanged(controllers)
            }
            // 优先用通知监听组件注册（change 回调对全部 UID 会话生效）；
            // 但该组件依赖 LiveLyricService 通知监听被系统启用，MIUI 等 ROM 会拦截，
            // 未启用时 addOnActiveSessionsChangedListener(component) 会抛 SecurityException，
            // 不可因此禁用整条会话跟踪——捕获后回退 null 注册（SystemUI 系统 UID 下
            // getActiveSessions(null) 仍能读到全部会话，再靠下方轮询补足 change 缺口）。
            var usedComponent = false
            if (component != null) {
                runCatching { manager.addOnActiveSessionsChangedListener(listener, component) }
                    .onSuccess { usedComponent = true }
                    .onFailure { e ->
                        HookLogger.w(TAG, "组件式会话监听注册失败，回退 null: ${e.message}")
                    }
            }
            if (!usedComponent) {
                runCatching { manager.addOnActiveSessionsChangedListener(listener, null) }
            }
            mediaSessionManager = manager
            localSessionsListener = listener
            // 立即拉取一次已存在的活动会话（覆盖系统启动/播放器早于模块加载的场景）
            onLocalActiveMediaSessionsChanged(readActiveSessions(manager, component))
            // 周期轮询兜底：MIUI 下 change 回调对其他 UID 会话可能不触发，
            // 每 [LOCAL_SESSION_POLL_INTERVAL_MS] 重拉快照，避免“重启后永不显示”。
            localSessionPollJob = fallbackScope.launch {
                while (isActive) {
                    delay(LOCAL_SESSION_POLL_INTERVAL_MS)
                    runCatching { onLocalActiveMediaSessionsChanged(readActiveSessions(manager, component)) }
                }
            }
            HookLogger.i(
                TAG,
                "SystemUI 本地媒体会话跟踪已启动, component=${if (usedComponent) component?.className else "null(回退)"}",
            )
        }.onFailure { error ->
            mediaSessionManager = null
            localSessionsListener = null
            localSessionPollJob?.cancel()
            localSessionPollJob = null
            HookLogger.w(TAG, "SystemUI 本地媒体会话跟踪不可用，回退 app 快照: reason=${error.message}")
        }
    }

    private fun onLocalActiveMediaSessionsChanged(controllers: List<MediaController>?) {
        val packages = controllers?.mapNotNull { it.packageName }?.toSet()
        activeMediaSessionGate.updateLocal(packages)
        diagnostic("stage=local_media_sessions, packages=${packages?.sorted()}")
        MediaCardDiagnosticLogger.log(
            stage = "media_session",
            event = "local_sessions_changed",
            details = "packages=${packages?.sorted()},activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},blocked=${activeCentralPlayerPackageName?.let(activeMediaSessionGate::isBlocked)}",
        )
        evaluateActiveMediaSessionGate()
        // 通用兜底改为真正的内部 Provider：把“无专用 Provider 的播放器”经
        // LyriconFactory.createProvider 注册，由 UniversalFallbackProvider 观察
        // MediaSession 并把基础歌曲推给 Central，复用与内置 Provider 完全相同的发布链路。
        universalFallbackProvider?.onSessionsChanged(controllers)
    }

    private fun unregisterLocalMediaSessionTracker() {
        val manager = mediaSessionManager
        val listener = localSessionsListener
        localSessionPollJob?.cancel()
        localSessionPollJob = null
        if (manager != null && listener != null) {
            runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
        }
        mediaSessionManager = null
        localSessionsListener = null
        activeMediaSessionGate.updateLocal(null)
    }

    /**
     * 系统级“是否有音频正在播放”。快照为空但音频在放时，说明 app 侧
     * 通知监听器失明（`150212` 真机证伪）或存在无会话音频，门控必须放行。
     * 取不到 AudioManager 时按“在放”处理，保持 fail-open。
     */
    private fun isAnyMusicActive(): Boolean {
        val context = app ?: return true
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
        return audioManager?.isMusicActive ?: true
    }
    private var lastTimingDiagnosticAtMs = 0L
    private var lastTimingDiagnosticPosition = -1L
    private var lastTimingDiagnosticState: String? = null
    private var lastCentralPositionDiagnosticAtMs = 0L

    private enum class OnlineTranslationPublicationStage {
        SINGLE,
        RACE_FIRST,
        RACE_FINAL,
        RACE_FINAL_COMMIT,
    }

    private data class PendingOnlineTranslationCommit(
        val generation: Int,
        val baseSong: LocalSong,
        val selection: OnlineTranslationSelection,
        val targetPosition: Long,
    )

    private val appleMediaMonitor = object : Runnable {
        override fun run() {
            if (sink == null) return
            observeAppleMediaSession()
            mainHandler.postDelayed(this, APPLE_MEDIA_MONITOR_INTERVAL_MS)
        }
    }

    @Volatile
    private var centralAppleProviderActive = false
    @Volatile
    private var centralAppleSongAvailable = false


    override fun isAvailable(): Boolean = true

    override fun start(sink: LyricSink) {
        diagnostic(
            "stage=source_start_requested, appPresent=${app != null}, " +
                "prefsPresent=${prefs != null}, subscriberPresent=${subscriber != null}, " +
                "directBridgePresent=${directBridge != null}",
        )
        if (this.subscriber != null) {
            HookLogger.d(TAG, "跳过重复启动: reason=already_running")
            diagnostic(
                "stage=source_start_skipped, reason=already_running, " +
                    "subscriberType=${subscriber?.javaClass?.name}",
            )
            return
        }
        this.sink = sink
        val application = app ?: run {
            HookLogger.w(TAG, "数据源启动延后: reason=application_unavailable")
            return
        }
        registerLocalMediaSessionTracker()
        diagnostic("stage=direct_bridge_starting")
        directBridge = AppleMusicDirectBridge(application, this).also { it.start() }
        diagnostic("stage=direct_bridge_started")
        initializeSubscriber(application)
        // 通用兜底 Provider：注册成真正的 Lyricon Provider，复用与内置 Provider 完全相同的发布链路，
        // 由 UniversalFallbackProvider 观察 MediaSession 并把基础歌曲推给 Central。
        universalFallbackProvider = UniversalFallbackProvider(
            application = application,
            isFallbackAllowedFor = { isFallbackAllowedFor(it) },
            onDiagnostic = { _, msg -> diagnostic("stage=universal_fallback, $msg") },
        ).also { it.start() }
        universalFallbackProvider?.reconsider()
        startAppleMediaMonitor()
        HookLogger.i(TAG, "数据源已启动")
        diagnostic(
            "stage=source_start_completed, subscriberType=${subscriber?.javaClass?.name}, " +
                "directBridgePresent=${directBridge != null}",
        )
    }

    override fun stop() {
        stopAppleMediaMonitor()
        unregisterLocalMediaSessionTracker()
        cancelFallback(clearAppleSong = true, reason = "source_stopped")
        cancelThirdPartyFallback(reason = "source_stopped")
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "source_stopped"
        )
        try {
            directBridge?.stop()
            directBridge = null
            subscriber?.unsubscribeActivePlayer(activePlayerListener)
            subscriber?.unregister()
            subscriber?.destroy()
        } catch (e: Exception) {
            HookLogger.e(TAG, "清理歌词订阅连接失败", e)
        } finally {
            centralAppleProviderActive = false
            centralAppleSongAvailable = false
            activeCentralPlayerPackageName = null
            activeProviderPackageName = null
            currentPublishedAppleSong = null
            currentThirdPartySong = null
            currentPublishedThirdPartySong = null
            currentPublishedAppleOnlineTranslationMatched = false
            appleMediaPositionReference = null
            appleDirectPositionReference = null
            currentDirectAppleSongId = null
            universalFallbackProvider?.release()
            universalFallbackProvider = null
            subscriber = null
            centralPlaybackPositionWitness.reset()
            sink?.onStop()
            sink = null
        }
        HookLogger.i(TAG, "数据源已停止")
    }

    /**
     * 判断某个播放器包是否允许走通用兜底：
     * - Apple Music 走专属直连桥，不走兜底；
     * - 若已有“非兜底自身”的专属 Provider 接管该包（activeProviderPackageName 指向真实
     *   提供器而非 BUILT_IN_PROVIDER_PACKAGE），则兜底让步，避免双源重复发布；
     * - 兜底自身（BUILT_IN_PROVIDER_PACKAGE）接管时仍允许继续兜底。
     */
    private fun isFallbackAllowedFor(playerPackage: String): Boolean {
        if (playerPackage == APPLE_MUSIC_PACKAGE) return false
        // 通用兜底必须走歌词白名单：与 app 进程 AppLyricSink 一致，
        // 未列入白名单的包（如视频 App）不应上岛，否则会占用歌词位置并干扰其他播放器。
        if (!isWhitelistedForLyrics(playerPackage)) return false
        if (activeCentralPlayerPackageName == playerPackage &&
            activeProviderPackageName != null &&
            activeProviderPackageName != BUILT_IN_PROVIDER_PACKAGE &&
            activeProviderPackageName != UNIVERSAL_FALLBACK_PROVIDER_PACKAGE
        ) {
            return false
        }
        return true
    }

    /**
     * 读取歌词白名单（[ServiceConstants.KEY_NOTIFICATION_WHITELIST]）。
     * 与 AppLyricSink 行为对齐：包必须在白名单内才允许上岛。
     * 白名单为空时视为未配置，沿用旧行为放行（避免影响未配置白名单的用户）。
     */
    private fun isWhitelistedForLyrics(playerPackage: String): Boolean {
        // LSPosed / Root 模式下，用户唯一可见的白名单入口是“超级岛 → 应用白名单”，
        // 对应 KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST。通用兜底必须用这个
        // 白名单过滤包名，否则视频 App 等非白名单应用也会上岛。
        val enabledPackages = runCatching {
            prefs?.getStringSet(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST, null)
        }.getOrElse { null }
        return IslandAlbumCoverWhitelist.isEnabled(enabledPackages, playerPackage)
    }

    fun initialize(
        app: Application,
        prefs: android.content.SharedPreferences?,
        onCentralConnected: (() -> Unit)? = null,
        onCentralConnectTimeout: (() -> Unit)? = null,
    ) {
        this.app = app
        this.prefs = prefs
        registerLocalMediaSessionTracker()
        onActiveMediaSessionSnapshotChanged(
            prefs?.getString(RootConstants.KEY_ACTIVE_MEDIA_SESSION_PACKAGES, null),
            reason = "source_initialized",
        )
        this.onCentralConnected = onCentralConnected
        this.onCentralConnectTimeout = onCentralConnectTimeout
        diagnostic(
            "stage=source_initialized, appPackage=${app.packageName}, " +
                "prefsPresent=${prefs != null}",
        )

        LyriconDataBridge.onAiTranslationComplete = {
            BaseIslandRenderer.refreshActiveIsland()
        }
    }

    fun onPreferenceChanged(key: String?) {
        val packageName = resolveDelayPackageName(
            activeProviderPackageName,
            activeCentralPlayerPackageName,
        )
        if (packageName != null && key == providerDelayKey(packageName)) {
            activeProviderDelayMs = readProviderDelay(packageName)
            return
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION ||
            key == RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE ||
            OnlineTranslationSourcePreferences.isSourcePreference(key) ||
            OnlineTranslationSourcePreferences.isAppPreference(key)
        ) {
            mainHandler.post { applyOnlineTranslationPreferenceChange(key.orEmpty()) }
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA) {
            mainHandler.post(::applyOriginalMetadataPreferenceChange)
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS) {
            mainHandler.post(::applySimplifiedLyricsPreferenceChange)
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION) {
            mainHandler.post(::applyNativeOnlineTranslationPreferenceChange)
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS) {
            mainHandler.post(::applyMissingLyricsPreferenceChange)
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN) {
            mainHandler.post(::applyMandarinPinyinPreferenceChange)
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS) {
            mainHandler.post(::applyLunaBeatWordLyricsPreferenceChange)
        }
        if (key == RootConstants.KEY_HOOK_MANUAL_LYRIC_MATCH_REQUEST) {
            mainHandler.post(::applyManualLyricMatchRequest)
        }
    }

    /**
     * 「二次匹配」：用户在设置页手动搜索并选中候选后，按候选的来源与歌曲 ID 重新抓词，
     * 必要时再向四平台补翻译，最后走与在线兜底一致的发布链路。
     */
    private fun applyManualLyricMatchRequest() {
        val request = ManualLyricMatchRequest.decode(
            prefs?.getString(RootConstants.KEY_HOOK_MANUAL_LYRIC_MATCH_REQUEST, null),
        ) ?: return
        val source = request.sourceEnum ?: return
        val application = app ?: return
        val baseSong = currentPublishedThirdPartySong ?: currentThirdPartySong ?: run {
            diagnostic("二次匹配失败: 当前无三方播放歌曲")
            return
        }
        val currentTitle = normalizeIdentity(baseSong.name)
        val requestTitle = normalizeIdentity(request.currentTitle)
        if (requestTitle.isNotBlank() && currentTitle != requestTitle) {
            diagnostic(
                "二次匹配忽略: 当前歌曲已切换, request=${request.currentTitle}, " +
                    "current=${baseSong.name}",
            )
            return
        }
        val candidate = SongSearchResult(
            id = request.sourceSongId,
            title = request.title,
            artist = request.artist,
            album = request.album,
            duration = request.durationMs,
            source = source,
        )
        val playerPackage = activeCentralPlayerPackageName.orEmpty()
        diagnostic(
            "二次匹配开始: source=${source.name}, id=${request.sourceSongId}, " +
                "title=${request.title}, artist=${request.artist}, " +
                "album=${request.album.ifBlank { "无" }}",
        )
        fallbackScope.launch {
            try {
                val lines = OnlineLyricTargeter.fetchLyricsForCandidate(application, candidate)
                if (lines == null) {
                    diagnostic("二次匹配未取到歌词: source=${source.name}, id=${request.sourceSongId}")
                    return@launch
                }
                var matched = OnlineFallbackSongMapper.map(baseSong, lines) ?: return@launch
                val orderedSources = configuredOnlineSources()
                if (orderedSources.isNotEmpty() && needsOnlineEnrichment(matched)) {
                    val translationLines = fetchThirdPartyLyrics(
                        application = application,
                        playerPackage = playerPackage,
                        baseSong = baseSong,
                        album = request.album.takeIf { it.isNotBlank() },
                        order = orderedSources,
                        requireTranslation = true,
                    )
                    if (translationLines != null) {
                        matched = OnlineTranslationMatcher.apply(matched, translationLines).song
                    }
                }
                val result = matched
                mainHandler.post {
                    currentPublishedThirdPartySong = result
                    thirdPartyFallbackSongActive = false
                    thirdPartyMatchFailedIdentity = null
                    publishSong(result, restorePosition = true)
                    diagnostic(
                        "二次匹配完成: source=${source.name}, " +
                            "lines=${result.lyrics.orEmpty().size}, " +
                            "translations=${result.lyrics.orEmpty().count {
                                OnlineTranslationContentPolicy.isMeaningful(it.translation)
                            }}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostic("二次匹配异常: ${e.javaClass.simpleName}:${e.message}")
            }
        }
    }

    private fun applyLunaBeatWordLyricsPreferenceChange() {
        val nativeSong = currentAppleNativeSong ?: currentAppleSong ?: return
        cancelFallback(clearAppleSong = false, reason = "lunabeat_preference_changed")
        if (isLunaBeatWordLyricsEnabled()) {
            scheduleFallback(
                baseSong = nativeSong,
                delayMs = 0L,
                preferredSourceOverride = Source.LB,
                strictSource = hasAppleNativeLyrics(nativeSong) || !isFillMissingLyricsEnabled(),
            )
            return
        }
        if (currentAppleSong?.metadata
                ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE) == Source.LB.name
        ) {
            currentAppleSong = nativeSong
            currentAppleHasNativeLyrics = hasAppleNativeLyrics(nativeSong)
            fallbackSongActive = false
            stopMediaPositionPolling()
            publishAppleSong(nativeSong, restorePosition = true)
            if (!hasAppleNativeLyrics(nativeSong) && isFillMissingLyricsEnabled()) {
                scheduleFallback(baseSong = nativeSong, delayMs = 0L)
            }
        }
    }

    private fun applyNativeOnlineTranslationPreferenceChange() {
        val nativeSong = currentAppleSong
        val publishedSong = currentPublishedAppleSong

        if (!isNativeOnlineTranslationEnabled()) {
            directBridge?.clearOnlineTranslation(publishedSong?.id ?: nativeSong?.id)
            if (!isOnlineTranslationEnabledFor(APPLE_MUSIC_PACKAGE)) {
                cancelOnlineTranslation(
                    clearAttempt = true,
                    clearMatched = true,
                    reason = "online_translation_fully_disabled",
                )
            }
            return
        }

        if (currentPublishedAppleOnlineTranslationMatched && publishedSong != null) {
            directBridge?.publishOnlineTranslation(
                ApplePronunciationVisibilityPolicy.filterSong(
                    song = publishedSong,
                    hideMandarinPinyin = isHideMandarinPinyinEnabled(),
    )
)

            return
        }

        if (
            nativeSong != null &&
            !nativeSong.lyrics.isNullOrEmpty() &&
            needsOnlineEnrichment(nativeSong)
        ) {
            scheduleOnlineTranslation(nativeSong)
        }
    }

    private fun applyMissingLyricsPreferenceChange() {
        val nativeSong = currentAppleSong
        if (!isFillMissingLyricsEnabled()) {
            directBridge?.clearMissingLyricsSupplement(nativeSong?.id)
            return
        }
        if (nativeSong == null || !needsMissingLyricsSourceRecovery(nativeSong)) return
        scheduleFallback(nativeSong, 0L)
    }

    private fun applyMandarinPinyinPreferenceChange() {
        val nativeSong = currentAppleSong ?: return
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "mandarin_pinyin_preference_changed",
        )
        publishAppleSong(nativeSong, restorePosition = true)
        if (
            isAppleTranslationEnrichmentEnabled() &&
            !nativeSong.lyrics.isNullOrEmpty() &&
            needsOnlineEnrichment(nativeSong)
        ) {
            scheduleOnlineTranslation(nativeSong)
        }
    }

    private fun applyOriginalMetadataPreferenceChange() {
        val nativeSong = currentAppleSong ?: return
        originalMetadataRequestKey = null
        cancelFallback(clearAppleSong = false, reason = "original_metadata_preference_changed")
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "original_metadata_preference_changed",
        )
        val originalMetadataPlan = AppleOnlineTranslationRequestPolicy.originalMetadataLookupPlan(
            shouldRequestOriginalMetadataForOnlineLookup(nativeSong)
        )
        if (originalMetadataPlan.requestOriginalMetadata) {
            requestOriginalMetadata(nativeSong, "preference_changed")
        }
        if (
            !originalMetadataPlan.waitForResult &&
            needsMissingLyricsSourceRecovery(nativeSong) &&
            isOnlineTranslationEnabledFor(APPLE_MUSIC_PACKAGE)
        ) {
            scheduleFallback(nativeSong, 0L)
        } else if (
            !originalMetadataPlan.waitForResult &&
            !nativeSong.lyrics.isNullOrEmpty() &&
            needsOnlineEnrichment(nativeSong) &&
            isAppleTranslationEnrichmentEnabled()
        ) {
            scheduleOnlineTranslation(nativeSong)
        }
    }

    private fun applyOnlineTranslationPreferenceChange(key: String) {
        if (activeCentralPlayerPackageName != null &&
            activeCentralPlayerPackageName != APPLE_MUSIC_PACKAGE
        ) {
            val song = currentThirdPartySong ?: return
            cancelThirdPartyFallback(reason = "third_party_preference_changed")
            cancelOnlineTranslation(
                clearAttempt = true,
                clearMatched = true,
                reason = "third_party_preference_changed",
            )
            if (currentPublishedThirdPartySong != song) {
                currentPublishedThirdPartySong = song
                publishSong(song, restorePosition = true)
            }
            reevaluateThirdPartyOnlineMatching(song)
            return
        }

        val nativeSong = currentAppleSong ?: return
        val sourcePreferenceChanged = OnlineTranslationSourcePreferences.isSourcePreference(key)
        val overlayEnabled = isOnlineTranslationEnabledFor(APPLE_MUSIC_PACKAGE)
        val nativeEnabled = isNativeOnlineTranslationEnabled()

        if (!overlayEnabled && !nativeEnabled && !isFillMissingLyricsEnabled()) {
            val shouldRestoreNative = fallbackSongActive || onlineMatchedTranslationActive
            cancelFallback(clearAppleSong = false, reason = "online_translation_disabled")
            cancelOnlineTranslation(
                clearAttempt = true,
                clearMatched = true,
                reason = "online_translation_disabled",
            )
            if (shouldRestoreNative) {
                publishAppleSong(nativeSong, restorePosition = true)
            }
            return
        }

        if (needsMissingLyricsSourceRecovery(nativeSong)) {
            if (!overlayEnabled && !isFillMissingLyricsEnabled()) return
            val delayMs = if (sourcePreferenceChanged && fallbackSongActive) {
                0L
            } else {
                APPLE_LYRICS_GRACE_MS
            }
            scheduleFallback(nativeSong, delayMs)
            observeAppleMediaSession(force = true)
            return
        }

        if (!needsOnlineEnrichment(nativeSong)) return

        if (!overlayEnabled) {
            val shouldRestoreNative = fallbackSongActive || onlineMatchedTranslationActive
            cancelFallback(clearAppleSong = false, reason = "overlay_online_disabled")
            if (shouldRestoreNative) {
                publishAppleSong(nativeSong, restorePosition = true)
            }
        } else if (sourcePreferenceChanged && currentPublishedAppleSong != nativeSong) {
            publishAppleSong(nativeSong, restorePosition = true)
        }

        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = false,
            reason = if (sourcePreferenceChanged) {
                "source_configuration_changed"
            } else {
                "online_translation_enabled"
            },
        )
        scheduleOnlineTranslation(nativeSong)
    }

    private fun providerDelayKey(packageName: String): String {
        return RootConstants.KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX + packageName
    }

    private fun resolveDelayPackageName(
        providerPackageName: String?,
        playerPackageName: String?,
    ): String? = when {
        providerPackageName == null -> playerPackageName
        providerPackageName == BUILT_IN_PROVIDER_PACKAGE -> playerPackageName ?: providerPackageName
        else -> providerPackageName
    }

    private fun readProviderDelay(packageName: String): Int {
        return prefs?.getInt(
            providerDelayKey(packageName),
            RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
        )?.coerceIn(
            RootConstants.MIN_HOOK_LYRICON_PROVIDER_DELAY,
            RootConstants.MAX_HOOK_LYRICON_PROVIDER_DELAY
        ) ?: RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
    }

    private fun handleAppleSong(incomingSong: LocalSong?) {
        val previousSong = currentAppleSong
        if (incomingSong != null && !isMissingLyricsSupplement(incomingSong)) {
            currentAppleNativeSong = incomingSong
        }
        val sameTrack = previousSong != null && incomingSong != null &&
            isSameTrack(previousSong, incomingSong)
        val authoritativeLyricsSource = confirmedLyricsSourceSelection
            ?.takeIf { selection ->
                sameTrack &&
                    selection.songId == previousSong.id &&
                    selection.songId == incomingSong.id
            }
            ?.source
        val song = mergeMissingLyricsSupplementMetadata(
            previousSong = previousSong,
            incomingSong = incomingSong,
            sameTrack = sameTrack,
            authoritativeSource = authoritativeLyricsSource,
        )
        val authoritativeNativeTransition = sameTrack &&
            isMissingLyricsSupplement(previousSong) &&
            hasConfirmedAppleNativeLyrics(song)
        if (authoritativeNativeTransition) {
            diagnostic(
                "Apple Music 原生歌词权威接管: id=${song?.id}, " +
                    "previousLines=${previousSong.lyrics.orEmpty().size}, " +
                    "nativeLines=${song?.lyrics.orEmpty().size}"
            )
        }
        if (song === previousSong && incomingSong !== previousSong && authoritativeLyricsSource != null) {
            diagnostic(
                "忽略过期 Apple Music 歌词来源回传: id=${previousSong?.id}, " +
                    "authoritative=$authoritativeLyricsSource, incoming=" +
                    incomingSong?.metadata
                        ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
            )
        }
        diagnostic(
            "Apple Music 歌曲入口: id=${song?.id}, title=${song?.name}, " +
                "artist=${song?.artist}, duration=${song?.duration}, " +
                "lyrics=${song?.lyrics.orEmpty().size}, supplement=${isMissingLyricsSupplement(song)}, " +
                "onlineEnabled=${isOnlineTranslationEnabledFor(APPLE_MUSIC_PACKAGE)}"
        )
        val preservesCurrentLyrics = previousSong != null && song != null &&
            AppleSongUpdatePolicy.shouldPreserveCurrentLyrics(previousSong, song, sameTrack)
        if (preservesCurrentLyrics) {
            debug("忽略同一首歌的空歌词降级: title=${song.name}")
            return
        }
        val originalMetadataChanged = sameTrack &&
            AppleOnlineTranslationRequestPolicy.originalMetadataChanged(previousSong, song)
        val repeatedEmptySong = sameTrack && song != null && !originalMetadataChanged &&
            song.lyrics.isNullOrEmpty() &&
            (fallbackSongActive || fallbackDelayRunnable != null || fallbackJob?.isActive == true)
        if (repeatedEmptySong) {
            currentAppleSong = song
            currentAppleHasNativeLyrics = false
            debug("忽略同一首歌的重复空歌词占位: title=${song.name}")
            return
        }
        // 原生歌词与无歌词补充共用同一套在线翻译匹配任务。同一首歌的后续
        // Apple 回调只更新基准歌词，不能取消正在进行中的翻译任务；否则保留的
        // attempt key 会让兜底结果里的重新调度被误判为重复请求。
        val repeatedLyricsNeedingEnrichment = shouldKeepRunningAppleEnrichment(
            sameTrack = sameTrack,
            authoritativeNativeTransition = authoritativeNativeTransition,
            hasLyrics = !song?.lyrics.isNullOrEmpty(),
            needsEnrichment = needsOnlineEnrichment(song),
            originalMetadataChanged = originalMetadataChanged,
            enrichmentRunning =
                onlineTranslationJob?.isActive == true || onlineMatchedTranslationActive,
        )
        if (repeatedLyricsNeedingEnrichment) {
            currentAppleSong = song
            currentAppleHasNativeLyrics = hasAppleNativeLyrics(song)
            debug("忽略同一首歌的重复待补全歌词: title=${song?.name}")
            return
        }
        cancelFallback(clearAppleSong = false, reason = "apple_song_updated")
        val incomingHasTranslation = hasTranslation(song)
        val incomingNeedsEnrichment = needsOnlineEnrichment(song)
        cancelOnlineTranslation(
            clearAttempt = shouldClearAppleOnlineTranslationAttempt(
                sameTrack = sameTrack,
                authoritativeNativeTransition = authoritativeNativeTransition,
                needsEnrichment = incomingNeedsEnrichment,
                originalMetadataChanged = originalMetadataChanged,
            ),
            clearMatched = true,
            reason = "apple_song_updated"
        )
        currentAppleSong = song
        currentAppleHasNativeLyrics = hasAppleNativeLyrics(song)
        if (!sameTrack) {
            appleSongGeneration += 1
            appleMediaPositionReference = null
            appleDirectPositionReference = null
            lastAdjustedPosition = 0L
            originalMetadataRequestKey = null
            temporaryTranslationSource = null
            temporaryPronunciationSource = null
            pendingTranslationSourceRequest = null
            pendingPronunciationSourceRequest = null
            pendingLyricsSourceRequest = null
            confirmedLyricsSourceSelection = null
            currentAppleNativeSong = incomingSong?.takeUnless(::isMissingLyricsSupplement)
            refreshAppleMediaPositionReference()
        }
        val originalMetadataPlan = AppleOnlineTranslationRequestPolicy.originalMetadataLookupPlan(
            song != null && shouldRequestOriginalMetadataForOnlineLookup(song)
        )
        pronunciationDiagnostic(
            "stage=request_entry_gate, id=${song?.id}, generation=$appleSongGeneration, " +
                "prefsPresent=${prefs != null}, matchingEnabled=${isAppleTranslationEnrichmentEnabled()}, " +
                "lyrics=${song?.lyrics.orEmpty().size}, needsEnrichment=${needsOnlineEnrichment(song)}, " +
                "titlePresent=${!song?.name.isNullOrBlank()}, " +
                "requestOriginal=${originalMetadataPlan.requestOriginalMetadata}, " +
                "waitOriginal=${originalMetadataPlan.waitForResult}, sameTrack=$sameTrack, " +
                "originalMetadataChanged=$originalMetadataChanged"
        )
        if (song != null && originalMetadataPlan.requestOriginalMetadata) {
            requestOriginalMetadata(song, "setting_enabled")
        }

        runCatching {
            publishAppleSong(song, restorePosition = sameTrack)
        }.onFailure {
            debugError("Apple Music 歌曲发布失败: title=${song?.name}", it)
        }

        if (
            song != null &&
            !isMissingLyricsSupplement(song) &&
            isLunaBeatWordLyricsEnabled()
        ) {
            scheduleFallback(
                baseSong = song,
                delayMs = 0L,
                preferredSourceOverride = Source.LB,
                strictSource = hasAppleNativeLyrics(song) || !isFillMissingLyricsEnabled(),
            )
        } else if (
            song != null &&
            needsMissingLyricsSourceRecovery(song) &&
            (
                isOnlineTranslationEnabledFor(APPLE_MUSIC_PACKAGE) ||
                    isFillMissingLyricsEnabled()
                )
        ) {
            HookLogger.i(
                TAG,
                "Apple Music 原生歌词未返回，立即预取在线候选: title=${song.name}"
            )
            // 候选检索可以与 Apple 原生请求并行；Apple 进程内的 takeover gate
            // 仍会在原生状态未确认前禁止呈现，因此这里不再额外等待 5 秒。
            scheduleFallback(song, 0L)
        }
        if (
            song != null &&
            !originalMetadataPlan.waitForResult &&
            hasAppleLyricsForOnlineEnrichment(
                song = song,
                confirmedNativeLyrics = hasAppleNativeLyrics(song),
            ) &&
            incomingNeedsEnrichment &&
            isAppleTranslationEnrichmentEnabled()
        ) {
            HookLogger.i(
                TAG,
                "Apple Music 歌词待补全: title=${song.name}, " +
                    "supplement=${isMissingLyricsSupplement(song)}, " +
                    "lines=${song.lyrics.orEmpty().size}, " +
                    "hasTranslation=$incomingHasTranslation"
            )
            if (originalMetadataChanged) {
                HookLogger.i(
                    TAG,
                    "Apple Music 原名已更新，重新匹配在线翻译: " +
                        "title=${song.name}, originalTitle=${song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE)}"
                )
            }
            scheduleOnlineTranslation(song)
        }
    }

    private fun isMissingLyricsSupplement(song: LocalSong?): Boolean = song?.metadata
        ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
        .toBoolean()

    private fun hasAppleNativeLyrics(song: LocalSong?): Boolean =
        !song?.lyrics.isNullOrEmpty() && !isMissingLyricsSupplement(song)

    /**
     * A cache migrated from a pre-source-selection build has no selected source. Re-query it
     * once so the Apple process receives a truthful source list instead of inventing a label.
     */
    private fun needsMissingLyricsSourceRecovery(song: LocalSong?): Boolean =
        song != null && (
            song.lyrics.isNullOrEmpty() ||
                (
                    isMissingLyricsSupplement(song) &&
                        song.metadata
                            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
                            .isNullOrBlank()
                    )
            )

    private fun handleThirdPartySong(
        song: LocalSong?,
        universalFallback: Boolean = false,
    ) {
        val previousSong = currentThirdPartySong
        val sameTrack = previousSong != null && song != null && isSameTrack(previousSong, song)
        val sameContent = sameTrack && previousSong == song
        if (sameContent && (onlineTranslationJob?.isActive == true ||
                onlineMatchedTranslationActive ||
                thirdPartyFallbackJob?.isActive == true ||
                thirdPartyFallbackSongActive)
        ) {
            currentThirdPartySong = song
            debug("忽略同一首歌的重复三方歌曲回调: title=${song.name}")
            return
        }
        val fallbackPending = thirdPartyFallbackDelayRunnable != null ||
            thirdPartyFallbackJob?.isActive == true || thirdPartyFallbackSongActive
        val preferOnline = isSaltPreferOnlineEnabled()
        if (
            sameTrack && fallbackPending &&
            (preferOnline || song.lyrics.isNullOrEmpty() || isPlaceholderLyrics(song))
        ) {
            // 在线兜底进行中：椒盐 Pack 重复发来的占位，或“优先使用在线源”下
            // 迟到的本地歌词，都不打断在线结果。
            currentThirdPartySong = song
            debug("忽略同一首歌的三方回调（在线兜底进行中）: title=${song.name}")
            return
        }
        cancelThirdPartyFallback(reason = "third_party_song_updated")
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "third_party_song_updated",
        )
        // 匹配失败已展示报错占位时，同一首歌的重复占位回调（Provider 重新发布基础歌曲）
        // 不再覆盖报错，直到切歌或偏好变化触发重新匹配。
        if (song != null &&
            thirdPartyMatchFailedIdentity == songIdentity(song) &&
            song.lyrics.isNullOrEmpty()
        ) {
            currentThirdPartySong = song
            debug("忽略同曲重复占位（匹配失败报错展示中）: title=${song.name}")
            return
        }
        currentThirdPartySong = song
        currentPublishedThirdPartySong = song
        publishSong(song, restorePosition = sameTrack)
        if (song == null) return
        val playerPackage = activeCentralPlayerPackageName
        // 无 Provider 接管（activeProviderPackageName == null）即视为通用兜底播放器：
        // 后续歌曲从订阅回调进来时 universalFallback 可能为 false，这里统一按「无 Provider」判定，
        // 保证始终走 LRCLIB 兜底取词而不是四平台整首取词。
        // 通用兜底判定：显式 universalFallback、无 Provider 接管（activeProviderPackageName == null），
        // 或兜底 Provider（BUILT_IN_PROVIDER_PACKAGE）发布的歌曲——三者在订阅回调里都按兜底处理，
        // 保证始终走 LRCLIB 兜底取词且绕过单 App 在线开关。
        val universal = universalFallback ||
            activeProviderPackageName == null ||
            activeProviderPackageName == BUILT_IN_PROVIDER_PACKAGE ||
            activeProviderPackageName == UNIVERSAL_FALLBACK_PROVIDER_PACKAGE
        // 通用兜底（无 Provider 播放器）必须把歌词归属注册到桥，否则岛渲染器
        // 依赖 currentLyricPackageName 匹配岛时拿到空值而直接跳过，兜底歌词无法上岛。
        if (universal) LyriconDataBridge.updateLyricPackage(activeCentralPlayerPackageName)
        // 通用兜底绕过单 App 在线开关：该场景下播放器已无任何歌词来源。
        if (!universal && !isThirdPartyOnlineEnabledFor(playerPackage)) return
        // 单行「歌名 - 歌手」占位歌词（无歌词曲目下发或本地生成）不算真实歌词，
        // 否则会被误判为「已有本地歌词」而跳过整首兜底取词（酷狗概念版即为此类）。
        val hasLocalLyrics = !song.lyrics.isNullOrEmpty() && !isPlaceholderLyrics(song)
        if (playerPackage == OnlineTranslationSourcePreferences.SPOTIFY_PACKAGE &&
            !hasLocalLyrics
        ) {
            // Spotify 原生无歌词：直接用歌名 + 歌手去已启用平台整首取词（歌词 + 翻译）。
            scheduleThirdPartyFallback(
                baseSong = song,
                delayMs = 0L,
                universalFallback = universal,
            )
        } else if (playerPackage == OnlineTranslationSourcePreferences.SALT_PACKAGE &&
            (preferOnline || !hasLocalLyrics)
        ) {
            // 椒盐音乐：本地无歌词时在线兜底；“优先使用在线源”时立即在线取词。
            // 未开启优先在线源时先等本地歌词的宽限期，避免无谓的在线请求。
            val graceNeeded = !preferOnline && !hasLocalLyrics
            scheduleThirdPartyFallback(
                baseSong = song,
                delayMs = if (graceNeeded) SALT_LOCAL_LYRICS_GRACE_MS else 0L,
                universalFallback = universal,
            )
        } else if (!hasLocalLyrics) {
            // 通用兜底：任何播放器的歌曲完全无歌词（且无占位行）时，
            // 同样流转到已启用在线源（含 LRCLIB）整首取词，实现“所有播放器必须匹配”。
            scheduleThirdPartyFallback(
                baseSong = song,
                delayMs = 0L,
                universalFallback = universal,
            )
        } else if (needsOnlineEnrichment(song)) {
            scheduleOnlineTranslation(song)
        }
    }

    private fun publishAppleSong(
        song: LocalSong?,
        restorePosition: Boolean,
        onlineTranslationMatched: Boolean = false,
        publishToSink: Boolean = true,
    ) {
        if (!publishToSink) return
        currentPublishedAppleSong = song
        currentPublishedAppleOnlineTranslationMatched = onlineTranslationMatched
        if (BuildConfig.DEBUG) {
            HookLogger.i(
                TAG,
                "[debug] Timing publish: songId=${song?.id}, restorePosition=$restorePosition, " +
                    "lastAdjustedPosition=$lastAdjustedPosition, " +
                    "onlineTranslationMatched=$onlineTranslationMatched, " +
                    "centralPlayer=$activeCentralPlayerPackageName, fallback=$fallbackSongActive"
            )
        }
        publishSong(
            song = AppleSongDisplayPolicy.copyForDisplay(song)
                ?.let(::filterApplePronunciationForDisplay)
                ?.let(::simplifyAppleSongForDisplay),
            restorePosition = restorePosition,
            onlineTranslationMatched = onlineTranslationMatched
        )
    }

    private fun publishSong(
        song: LocalSong?,
        restorePosition: Boolean,
        onlineTranslationMatched: Boolean = false
    ) {
        val preservedSameSongState = restorePosition &&
            song != null &&
            !song.lyrics.isNullOrEmpty() &&
            LyriconDataBridge.replaceSameSongContent(song)
        if (!preservedSameSongState) {
            LyriconDataBridge.updateSong(song)
        }
        if (onlineTranslationMatched) {
            sink?.onOnlineTranslationMatched(song)
        } else {
            sink?.onSongChanged(song)
        }
        BaseIslandRenderer.refreshActiveIsland()
        if (restorePosition && song != null && !song.lyrics.isNullOrEmpty()) {
            sink?.onPositionChanged(lastAdjustedPosition)
        }
    }

    private fun applySimplifiedLyricsPreferenceChange() {
        val song = currentPublishedAppleSong ?: return
        publishAppleSong(
            song = song,
            restorePosition = true,
            onlineTranslationMatched = currentPublishedAppleOnlineTranslationMatched
        )
    }

    private fun simplifyAppleSongForDisplay(song: LocalSong): LocalSong {
        if (!isSimplifyTraditionalLyricsEnabled()) return song
        val application = app ?: return song
        return TraditionalLyricsSimplifier.simplify(song) { text ->
            ChineseUtils.toSimplified(application, text)
        }
    }

    private fun filterApplePronunciationForDisplay(song: LocalSong): LocalSong =
        ApplePronunciationVisibilityPolicy.filterSong(
            song = song,
            hideMandarinPinyin = isHideMandarinPinyinEnabled(),
        )

    private fun simplifyAppleTextForDisplay(text: String?): String? {
        text ?: return null
        if (!isSimplifyTraditionalLyricsEnabled()) return text
        val application = app ?: return text
        return ChineseUtils.toSimplified(application, text)
    }

    private fun scheduleFallback(
        baseSong: LocalSong,
        delayMs: Long,
        preferredSourceOverride: Source? = null,
        strictSource: Boolean = false,
    ) {
        val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
            ?.takeIf { request ->
                request.contentType == "lyrics" &&
                    preferredSourceOverride == request.requestedSource
            }
        val fallbackEnabled = isOnlineTranslationEnabledFor(APPLE_MUSIC_PACKAGE)
        val supplementEnabled = isFillMissingLyricsEnabled() || isLunaBeatWordLyricsEnabled()
        val lunaBeatRequested = preferredSourceOverride == Source.LB ||
            (preferredSourceOverride == null && isLunaBeatWordLyricsEnabled())
        if (
            (baseSong.name.isNullOrBlank() && !lunaBeatRequested) ||
            (!fallbackEnabled && !supplementEnabled)
        ) {
            return
        }
        val configuredSources = OnlineTranslationSourcePreferences.orderedSources(prefs)
        if (configuredSources.isEmpty() && !lunaBeatRequested) return
        val previousGeneration = fallbackGeneration
        val previousJobActive = fallbackJob?.isActive == true
        val previousDelayPending = fallbackDelayRunnable != null
        fallbackGeneration += 1
        val generation = fallbackGeneration
        fallbackDelayRunnable?.let(mainHandler::removeCallbacks)
        fallbackDelayRunnable = null
        fallbackJob?.cancel()
        fallbackJob = null
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "fallback_scheduled",
            details = "generation=$previousGeneration->$generation,delayMs=$delayMs," +
                "strict=$strictSource,preferred=${preferredSourceOverride ?: "none"}," +
                "order=${configuredSources.joinToString("+")}," +
                "cancelledJobActive=$previousJobActive," +
                "cancelledDelayPending=$previousDelayPending",
        )

        val delayedSearch = Runnable {
            if (generation != fallbackGeneration) {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "fallback_delay_abandoned",
                    details = "generation=$generation,currentGeneration=$fallbackGeneration",
                )
                return@Runnable
            }
            fallbackDelayRunnable = null
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "fallback_worker_launching",
                details = "generation=$generation",
            )
            val application = app
            if (application == null) {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "fallback_worker_abandoned",
                    details = "generation=$generation,reason=application_unavailable",
                )
                diagnostic(
                    "Apple Music 在线兜底无法启动: reason=application_unavailable, " +
                        "title=${baseSong.name}"
                )
                return@Runnable
            }
            fallbackJob = fallbackScope.launch {
                val mutexWaitStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "fallback_mutex_wait_started",
                    details = "generation=$generation",
                )
                try {
                    fallbackRequestMutex.withLock {
                        sourceSwitchCoreStage(
                            request = sourceSwitchRequest,
                            stage = "fallback_mutex_acquired",
                            details = "generation=$generation,waitMs=" +
                                ((SystemClock.elapsedRealtimeNanos() - mutexWaitStartedAtNanos) /
                                    1_000_000.0),
                        )
                        if (generation != fallbackGeneration) {
                            sourceSwitchCoreStage(
                                request = sourceSwitchRequest,
                                stage = "fallback_search_abandoned",
                                details = "generation=$generation," +
                                    "currentGeneration=$fallbackGeneration",
                            )
                            return@withLock
                        }
                        diagnostic(
                            "Apple Music 在线兜底开始: title=${baseSong.name}, " +
                                "artist=${baseSong.artist}, order=${configuredSources.joinToString("+")}"
                        )
                        val searchStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                        sourceSwitchCoreStage(
                            request = sourceSwitchRequest,
                            stage = "fallback_search_started",
                            details = "generation=$generation," +
                                "source=${preferredSourceOverride ?: "automatic"}",
                        )
                        val outcome = fetchAppleLyricsOutcome(
                            application = application,
                            baseSong = baseSong,
                            configuredSources = configuredSources,
                            preferredSourceOverride = preferredSourceOverride,
                            strictSource = strictSource,
                        )
                        sourceSwitchCoreStage(
                            request = sourceSwitchRequest,
                            stage = "fallback_search_finished",
                            details = "generation=$generation,elapsedMs=" +
                                ((SystemClock.elapsedRealtimeNanos() - searchStartedAtNanos) /
                                    1_000_000.0) +
                                ",selected=${outcome.selectedSource ?: "none"}," +
                                "lines=${outcome.lines?.size ?: 0}," +
                                "wordLines=${outcome.wordLines?.size ?: 0}," +
                                "statuses=${outcome.sourceStatuses.joinToString("+") {
                                    it.source + ":" + it.found
                                }}",
                        )
                        val applyPostedAtNanos = SystemClock.elapsedRealtimeNanos()
                        mainHandler.post {
                            val applyStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                            sourceSwitchCoreStage(
                                request = sourceSwitchRequest,
                                stage = "fallback_apply_main_started",
                                details = "generation=$generation,queueWaitMs=" +
                                    ((applyStartedAtNanos - applyPostedAtNanos) / 1_000_000.0),
                            )
                            try {
                                applyFallbackResult(
                                    generation = generation,
                                    baseSong = baseSong,
                                    outcome = outcome,
                                    application = application,
                                    fallbackEnabled = fallbackEnabled,
                                    requestedSource = preferredSourceOverride,
                                )
                            } finally {
                                sourceSwitchCoreStage(
                                    request = sourceSwitchRequest,
                                    stage = "fallback_apply_main_finished",
                                    details = "generation=$generation,elapsedMs=" +
                                        ((SystemClock.elapsedRealtimeNanos() - applyStartedAtNanos) /
                                            1_000_000.0),
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "fallback_worker_cancelled",
                        details = "generation=$generation,currentGeneration=$fallbackGeneration",
                    )
                    throw e
                } catch (e: Exception) {
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "fallback_worker_failed",
                        details = "generation=$generation,error=${e.javaClass.simpleName}",
                    )
                    debugError("Apple Music 在线兜底失败: title=${baseSong.name}", e)
                }
            }
        }
        fallbackDelayRunnable = delayedSearch
        diagnostic(
            "Apple Music 在线兜底已调度: title=${baseSong.name}, " +
                "delayMs=$delayMs, generation=$generation"
        )
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "fallback_delay_posted",
            details = "generation=$generation,delayMs=$delayMs",
        )
        mainHandler.postDelayed(delayedSearch, delayMs)
    }

    private suspend fun fetchAppleLyricsOutcome(
        application: Application,
        baseSong: LocalSong,
        configuredSources: List<Source>,
        preferredSourceOverride: Source?,
        strictSource: Boolean,
    ): OnlineLyricTargeter.FetchOutcome {
        val shouldTryLunaBeat = preferredSourceOverride == Source.LB ||
            (preferredSourceOverride == null && isLunaBeatWordLyricsEnabled())
        var lunaBeatStatus: AppleMissingLyricsSourceStatus? = null
        if (shouldTryLunaBeat) {
            val lookup = LunaBeatTtmlRepository.findWordTimedByAppleMusicId(
                context = application,
                appleMusicId = baseSong.id.orEmpty(),
            )
            lunaBeatStatus = lookup.toSourceStatus()
            lookup.match?.let { match ->
                return OnlineLyricTargeter.FetchOutcome(
                    lines = match.parsed.lrcLines,
                    wordLines = match.parsed.wordLines,
                    sourceStatuses = listOf(lunaBeatStatus),
                    selectedSource = Source.LB,
                    rawAppleTtml = match.rawTtml,
                    sourceLyricId = match.hubId,
                    sourceLyricSha256 = match.sha256,
                )
            }
            if (strictSource || preferredSourceOverride == Source.LB && !isFillMissingLyricsEnabled()) {
                return OnlineLyricTargeter.FetchOutcome(
                    sourceStatuses = listOfNotNull(lunaBeatStatus),
                )
            }
        }

        val onlinePreferredSource = preferredSourceOverride?.takeUnless { it == Source.LB }
        val onlineOutcome = OnlineLyricTargeter.fetchBestLyricWithNearMiss(
            context = application,
            pkgName = APPLE_MUSIC_PACKAGE,
            title = baseSong.name.orEmpty(),
            artist = baseSong.artist.orEmpty(),
            durationMs = baseSong.duration,
            originalTitle = baseSong.metadata
                ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
            originalArtist = baseSong.metadata
                ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST),
            preferOriginalMetadata = shouldPreferAppleOriginalMetadata(),
            preferredSource = onlinePreferredSource,
            fallbackToOtherSources = !strictSource,
            sourceOrder = if (strictSource && onlinePreferredSource != null) {
                listOf(onlinePreferredSource)
            } else {
                configuredSources
            },
            statusSourceOrder = if (isFillMissingLyricsEnabled()) {
                // 严格来源切换也要补齐其他来源的状态，否则弹窗
                // 会出现「未检索/检索失败」的假失败。
                OnlineTranslationSourcePreferences.defaultOrder
            } else {
                null
            },
            album = MediaMetadataHelper
                .getMediaInfo(application, APPLE_MUSIC_PACKAGE, HookLogger)
                .album,
            collectSourceStatuses = isFillMissingLyricsEnabled(),
        )
        return if (lunaBeatStatus == null) {
            onlineOutcome
        } else {
            onlineOutcome.copy(
                sourceStatuses = AppleMissingLyricsSourceMetadata.mergeStatuses(
                    previous = listOf(lunaBeatStatus),
                    incoming = onlineOutcome.sourceStatuses,
                )
            )
        }
    }

    private fun LunaBeatLookupResult.toSourceStatus(): AppleMissingLyricsSourceStatus =
        AppleMissingLyricsSourceStatus(
            source = Source.LB.name,
            searched = searched,
            found = match != null,
            wordTimed = match != null,
            lineCount = match?.parsed?.wordLines?.size ?: 0,
        )

    /** 全中文歌词不携带在线翻译：正文若全为中文，在线载荷的翻译列整体剔除。 */
    private fun stripFullyChineseTranslations(lines: List<LrcLine>): List<LrcLine> {
        if (!ChineseLyricsPolicy.isFullyChineseLrc(lines)) return lines
        return lines.map { it.copy(translation = null) }
    }

    private fun OnlineLyricTargeter.FetchOutcome.stripFullyChineseTranslations():
        OnlineLyricTargeter.FetchOutcome {
        val lines = lines ?: return this
        val stripped = stripFullyChineseTranslations(lines)
        return if (stripped === lines) this else copy(lines = stripped)
    }

    private fun applyFallbackResult(
        generation: Int,
        baseSong: LocalSong,
        outcome: OnlineLyricTargeter.FetchOutcome,
        application: Application,
        fallbackEnabled: Boolean,
        requestedSource: Source? = null,
    ) {
        // 全中文正文不携带在线翻译：无歌词兜底/来源切换载荷的翻译列（常见为伴唱标注）
        // 一并剔除，后续逐字与行级映射都以此为源。
        val outcome = outcome.stripFullyChineseTranslations()
        val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
            ?.takeIf { request ->
                request.contentType == "lyrics" &&
                    (requestedSource == null || requestedSource == request.requestedSource)
            }
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "fallback_result_applying",
            details = "generation=$generation,currentGeneration=$fallbackGeneration," +
                "selected=${outcome.selectedSource ?: "none"}," +
                "lines=${outcome.lines?.size ?: 0},wordLines=${outcome.wordLines?.size ?: 0}",
        )
        val nativeSong = currentAppleSong
        val sameTrack = nativeSong != null && isSameTrack(nativeSong, baseSong)
        val nativeSupplement = isMissingLyricsSupplement(nativeSong)
        val nativeSongHasNativeLyrics = hasAppleNativeLyrics(nativeSong)
        val pendingSourceRequest = pendingLyricsSourceRequest
        val manualLyricsSourceSwitch = requestedSource != null &&
            pendingSourceRequest?.songId == baseSong.id &&
            pendingSourceRequest?.requestedSource == requestedSource
        val automaticLunaBeatOverride = requestedSource == Source.LB &&
            outcome.selectedSource == Source.LB &&
            isLunaBeatWordLyricsEnabled()
        val requestStillCurrent = nativeSong != null && acceptsAppleOnlineLyricResult(
            generation = generation,
            currentGeneration = fallbackGeneration,
            sameTrack = sameTrack,
            currentNativeLyrics = currentAppleHasNativeLyrics,
            currentSongHasNativeLyrics = nativeSongHasNativeLyrics,
            manualSourceSwitch = manualLyricsSourceSwitch,
            automaticLunaBeatOverride = automaticLunaBeatOverride,
        )
        if (!requestStillCurrent) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "fallback_result_rejected",
                details = "generation=$generation,currentGeneration=$fallbackGeneration," +
                    "sameTrack=$sameTrack,currentNative=$currentAppleHasNativeLyrics," +
                    "currentSongHasNative=$nativeSongHasNativeLyrics," +
                    "manual=$manualLyricsSourceSwitch",
            )
            diagnostic(
                "Apple Music 在线兜底结果已过期: title=${baseSong.name}, " +
                    "generation=$generation, currentGeneration=$fallbackGeneration, " +
                    "sameTrack=$sameTrack, currentNative=$currentAppleHasNativeLyrics, " +
                    "currentSongHasNative=$nativeSongHasNativeLyrics, " +
                    "currentSupplement=$nativeSupplement, currentId=${nativeSong?.id}, " +
                    "manualLyricsSourceSwitch=$manualLyricsSourceSwitch, " +
                    "resultLines=${outcome.lines?.size ?: 0}, " +
                    "resultWordLines=${outcome.wordLines?.size ?: 0}, " +
                    "selected=${outcome.selectedSource?.name}, " +
                    "statuses=${outcome.sourceStatuses.joinToString { it.source + ":" + it.found }}"
            )
            return
        }

        fallbackJob = null
        var supplementSong: LocalSong? = null
        var enrichedLrcLines: List<LrcLine>? = null
        if (isFillMissingLyricsEnabled() || outcome.selectedSource == Source.LB) {
            val previousSourceInfo = AppleMissingLyricsSourceMetadata.decode(
                selectedSource = nativeSong.metadata
                    ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE),
                encodedStatuses = nativeSong.metadata
                    ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES),
            )
            val mergedStatuses = AppleMissingLyricsSourceMetadata.mergeStatuses(
                previous = previousSourceInfo?.statuses.orEmpty(),
                incoming = outcome.sourceStatuses,
            )
            val sourceInfo = AppleMissingLyricsSourceInfo(
                selectedSource = outcome.selectedSource?.name,
                statuses = mergedStatuses,
            )
            enrichedLrcLines = outcome.lines?.let { lines ->
                preservePreviousTranslations(nativeSong, lines)
            }
            val supplementLrcLines = enrichedLrcLines ?: outcome.lines
            val supplementBuildMode = when {
                !outcome.wordLines.isNullOrEmpty() -> "word"
                !supplementLrcLines.isNullOrEmpty() -> "line"
                else -> "none"
            }
            supplementSong = AppleMissingLyricsSongMapper.map(
                baseSong = baseSong,
                wordLines = outcome.wordLines,
                lrcLines = supplementLrcLines,
                sourceInfo = sourceInfo,
                rawAppleTtml = outcome.rawAppleTtml,
                sourceLyricId = outcome.sourceLyricId,
                sourceLyricSha256 = outcome.sourceLyricSha256,
            )
            diagnostic(
                "Apple Music 无歌词补充构建: id=${baseSong.id}, " +
                    "mode=$supplementBuildMode, " +
                    "wordLines=${outcome.wordLines?.size ?: 0}, " +
                    "lineLines=${supplementLrcLines?.size ?: 0}, " +
                    "builtLines=${supplementSong?.lyrics.orEmpty().size}"
            )
            if (supplementSong != null) {
                if (outcome.selectedSource == Source.LB) {
                    confirmedLyricsSourceSelection = ConfirmedLyricsSourceSelection(
                        songId = baseSong.id.orEmpty(),
                        source = Source.LB.name,
                    )
                }
                val publishStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                val published = directBridge?.publishMissingLyricsSupplement(supplementSong) == true
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "supplement_binder_published",
                    details = "generation=$generation,elapsedMs=" +
                        ((SystemClock.elapsedRealtimeNanos() - publishStartedAtNanos) /
                            1_000_000.0) +
                        ",published=$published,lines=${supplementSong.lyrics.orEmpty().size}," +
                        "mode=$supplementBuildMode",
                )
                diagnostic(
                    "Apple Music 无歌词补充回传: id=${supplementSong.id}, " +
                        "lines=${supplementSong.lyrics.orEmpty().size}, published=$published"
                )
                // 后续在线翻译请求必须把这份补充歌词当作匹配基准；空歌词的 Apple
                // 占位不能再作为 currentAppleSong，否则翻译结果会在 apply guard 被丢弃。
                currentAppleSong = supplementSong
                currentAppleHasNativeLyrics = false
            } else if (requestedSource == null) {
                directBridge?.clearMissingLyricsSupplement(baseSong.id)
                diagnostic("Apple Music 无歌词补充未命中: title=${baseSong.name}")
            } else {
                diagnostic(
                    "Apple Music 歌词来源未命中，保留当前补充歌词: " +
                        "id=${baseSong.id}, requested=$requestedSource"
                )
            }
            completePendingLyricsSourceRequest(
                song = supplementSong,
                requestedSource = requestedSource,
            )
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "fallback_result_applied",
                details = "generation=$generation,supplement=${supplementSong != null}," +
                    "source=${outcome.selectedSource ?: "none"}",
            )
        }

        if (!fallbackEnabled && supplementSong == null) {
            if (outcome.lines == null) {
                diagnostic("Apple Music 在线兜底未命中: title=${baseSong.name}")
                requestOriginalMetadata(baseSong, "lyrics_fallback_miss")
            }
            return
        }

        val fallbackSong = (enrichedLrcLines ?: outcome.lines)?.let {
            OnlineFallbackSongMapper.map(baseSong, it)
        }
        if (fallbackSong == null && supplementSong == null) {
            diagnostic("Apple Music 在线兜底未命中: title=${baseSong.name}")
            requestOriginalMetadata(baseSong, "lyrics_fallback_miss")
            return
        }
        fallbackSongActive = true
        MediaMetadataHelper.getPlaybackProgress(application, APPLE_MUSIC_PACKAGE)
            .position
            .takeIf { it >= 0L }
            ?.let { lastAdjustedPosition = it }
        val displayFallbackSong = supplementSong ?: requireNotNull(fallbackSong)
        HookLogger.i(
            TAG,
            "Apple Music 在线兜底命中: title=${baseSong.name}, " +
                "lines=${displayFallbackSong.lyrics.orEmpty().size}, " +
                "translations=${displayFallbackSong.lyrics.orEmpty().count {
                    OnlineTranslationContentPolicy.isMeaningful(it.translation)
                }}"
        )
        val fallbackHasTranslation = hasTranslation(displayFallbackSong)
        publishAppleSong(
            displayFallbackSong,
            restorePosition = true,
            onlineTranslationMatched = fallbackHasTranslation
        )
        if (!fallbackHasTranslation) {
            val onlineTranslationRunning =
                onlineTranslationJob?.isActive == true || onlineMatchedTranslationActive
            val onlineTranslationScheduled = supplementSong != null &&
                isAppleTranslationEnrichmentEnabled() &&
                scheduleOnlineTranslation(supplementSong)
            if (!onlineTranslationRunning && !onlineTranslationScheduled) {
                sink?.onOnlineTranslationUnavailable(displayFallbackSong)
            }
        } else if (supplementSong != null && isAppleTranslationEnrichmentEnabled()) {
            // 来源切换后的新正文可能复用了旧翻译，但行结构仍需要重新匹配；只要
            // 还有缺口，scheduleOnlineTranslation 会基于新载荷补齐，不会因为已有
            // 一部分翻译就跳过本次独立翻译请求。
            scheduleOnlineTranslation(supplementSong)
        }
        startMediaPositionPolling()
    }

    /**
     * 歌词来源切换的新载荷通常不带翻译。先按行时间轴把旧歌词里的翻译带过去，
     * 避免 Apple Music、超级岛和 AOD 在独立翻译链路完成前出现翻译空白。
     */
    private fun preservePreviousTranslations(
        previousSong: LocalSong?,
        lines: List<LrcLine>,
    ): List<LrcLine> {
        val previousLines = previousSong?.lyrics.orEmpty()
        return lines.map { line ->
            if (OnlineTranslationContentPolicy.isMeaningful(line.translation)) return@map line
            val normalizedText = normalizeLyricText(line.content)
            val previous = previousLines.firstOrNull { previousLine ->
                previousLine.begin == line.startTimeMs &&
                    normalizeLyricText(previousLine.text) == normalizedText
            } ?: previousLines.firstOrNull { it.begin == line.startTimeMs }
            val previousTranslation = previous?.translation
                ?.takeIf(OnlineTranslationContentPolicy::isMeaningful)
                ?: return@map line
            line.copy(translation = previousTranslation)
        }
    }

    private fun normalizeLyricText(text: String?): String =
        text.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun cancelFallback(clearAppleSong: Boolean, reason: String) {
        if (fallbackDelayRunnable != null || fallbackJob?.isActive == true || fallbackSongActive) {
            diagnostic(
                "Apple Music 在线兜底取消: reason=$reason, " +
                    "clearAppleSong=$clearAppleSong, title=${currentAppleSong?.name}"
            )
        }
        fallbackGeneration += 1
        fallbackDelayRunnable?.let(mainHandler::removeCallbacks)
        fallbackDelayRunnable = null
        fallbackJob?.cancel()
        fallbackJob = null
        fallbackSongActive = false
        stopMediaPositionPolling()
        if (clearAppleSong) {
            currentAppleSong = null
            currentAppleHasNativeLyrics = false
        }
    }

    /** 偏好变化后重新决定第三方歌曲的在线策略（椒盐支持在线歌词兜底与优先在线源）。 */
    private fun reevaluateThirdPartyOnlineMatching(song: LocalSong) {
        // 偏好变化允许对失败曲目重新匹配，清除报错占位的粘性状态。
        thirdPartyMatchFailedIdentity = null
        val playerPackage = activeCentralPlayerPackageName
        val matchingEnabled = isThirdPartyOnlineEnabledFor(playerPackage)
        val hasLyrics = !song.lyrics.isNullOrEmpty()
        val preferOnline = isSaltPreferOnlineEnabled()
        when {
            playerPackage == OnlineTranslationSourcePreferences.SALT_PACKAGE &&
                matchingEnabled && (preferOnline || !hasLyrics) ->
                scheduleThirdPartyFallback(song, 0L)
            matchingEnabled && hasLyrics && needsOnlineEnrichment(song) ->
                scheduleOnlineTranslation(song)
            else -> sink?.onOnlineTranslationUnavailable(song)
        }
    }

    /**
     * 椒盐音乐在线歌词兜底：优先使用在线源时立即取词，否则等待椒盐 Pack 的
     * 本地歌词结果，超时后在线兜底。
     */
    private fun scheduleThirdPartyFallback(
        baseSong: LocalSong,
        delayMs: Long,
        universalFallback: Boolean = false,
    ) {
        val playerPackage = activeCentralPlayerPackageName ?: return
        if (baseSong.name.isNullOrBlank()) return
        // 通用兜底（无 Provider 播放器）必须绕过单 App 在线开关：
        // 否则未装模块的未知播放器（如酷狗）既无本地歌词也无在线兜底，永远裸奔。
        val orderedSources = OnlineTranslationSourcePreferences.orderedSources(prefs)
        if (!universalFallback) {
            if (!isThirdPartyOnlineEnabledFor(playerPackage)) return
            if (orderedSources.isEmpty()) return
        }
        thirdPartyFallbackGeneration += 1
        val generation = thirdPartyFallbackGeneration
        thirdPartyFallbackDelayRunnable?.let(mainHandler::removeCallbacks)
        thirdPartyFallbackDelayRunnable = null
        thirdPartyFallbackJob?.cancel()
        thirdPartyFallbackJob = null
        thirdPartyFallbackSongActive = false

        val delayedSearch = Runnable {
            if (generation != thirdPartyFallbackGeneration) return@Runnable
            thirdPartyFallbackDelayRunnable = null
            val application = app
            if (application == null) {
                diagnostic(
                    "在线兜底无法启动: reason=application_unavailable, " +
                        "title=${baseSong.name}"
                )
                return@Runnable
            }
            thirdPartyFallbackJob = fallbackScope.launch {
                try {
                    fallbackRequestMutex.withLock {
                        if (generation != thirdPartyFallbackGeneration) return@withLock
                        diagnostic(
                            "在线兜底开始: title=${baseSong.name}, " +
                                "artist=${baseSong.artist}, " +
                                "preferOnline=${isSaltPreferOnlineEnabled()}"
                        )
                        val album = baseSong.metadata
                            ?.getString(LyricMetadataKeys.MEDIA_ALBUM)
                            ?.takeIf { it.isNotBlank() }
                            ?: MediaMetadataHelper
                                .getMediaInfo(application, playerPackage, HookLogger)
                                .album
                        diagnostic(
                            "在线兜底专辑: album=${album.ifBlank { "无" }}, " +
                                "title=${baseSong.name}, artist=${baseSong.artist}"
                        )
                        // 是否存在专用插件（内置插件/独立模块）在场：
                        // 自有通用兜底 Provider 与控制通道（BUILT_IN）不算专用插件。
                        val dedicatedProviderActive = activeProviderPackageName != null &&
                            activeProviderPackageName != UNIVERSAL_FALLBACK_PROVIDER_PACKAGE &&
                            activeProviderPackageName != BUILT_IN_PROVIDER_PACKAGE
                        val fallbackSong: LocalSong? = if (dedicatedProviderActive) {
                            // 规则 1：专用插件未命中 → 通用插件（LRCLIB）兜底 →
                            // 仍未命中直接报错「通用插件歌词匹配失败」，不再走四平台取词。
                            fetchThirdPartyLyrics(
                                application = application,
                                playerPackage = playerPackage,
                                baseSong = baseSong,
                                album = album,
                                order = listOf(Source.LRCLIB),
                                requireTranslation = false,
                            )
                                ?.let(::stripFullyChineseTranslations)
                                ?.let { OnlineFallbackSongMapper.map(baseSong, it) }
                                ?.let { lrclibSong ->
                                    enrichWithOnlineTranslations(
                                        application = application,
                                        playerPackage = playerPackage,
                                        baseSong = baseSong,
                                        album = album,
                                        lrclibSong = lrclibSong,
                                        orderedSources = orderedSources,
                                    )
                                }
                        } else {
                            // 规则 2：没有任何专用插件 → 通用插件（LRCLIB）未命中时，
                            // 允许继续走四平台在线源整首匹配歌词+翻译。
                            val lrclibSong = fetchThirdPartyLyrics(
                                application = application,
                                playerPackage = playerPackage,
                                baseSong = baseSong,
                                album = album,
                                order = listOf(Source.LRCLIB),
                                requireTranslation = false,
                            )
                                ?.let(::stripFullyChineseTranslations)
                                ?.let { OnlineFallbackSongMapper.map(baseSong, it) }
                            when {
                                lrclibSong != null -> enrichWithOnlineTranslations(
                                    application = application,
                                    playerPackage = playerPackage,
                                    baseSong = baseSong,
                                    album = album,
                                    lrclibSong = lrclibSong,
                                    orderedSources = orderedSources,
                                )

                                orderedSources.isNotEmpty() -> fetchThirdPartyLyrics(
                                    application = application,
                                    playerPackage = playerPackage,
                                    baseSong = baseSong,
                                    album = album,
                                    order = orderedSources,
                                    requireTranslation = false,
                                )
                                    ?.let(::stripFullyChineseTranslations)
                                    ?.let { OnlineFallbackSongMapper.map(baseSong, it) }

                                else -> null
                            }
                        }
                        mainHandler.post {
                            // 兜底结果回调运行在宿主 SystemUI 主线程，
                            // 任何异常都会导致 SystemUI 崩溃循环，必须整体捕获。
                            runCatching {
                                applyThirdPartyFallbackResult(
                                    generation = generation,
                                    baseSong = baseSong,
                                    fallbackSong = fallbackSong,
                                    universalFallback = universalFallback,
                                )
                            }.onFailure { error ->
                                debugError("在线兜底结果处理失败: title=${baseSong.name}", error)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    debugError("在线兜底失败: title=${baseSong.name}", e)
                }
            }
        }
        thirdPartyFallbackDelayRunnable = delayedSearch
        diagnostic(
            "在线兜底已调度: title=${baseSong.name}, delayMs=$delayMs, " +
                "generation=$generation"
        )
        if (delayMs <= 0L) {
            mainHandler.post(delayedSearch)
        } else {
            mainHandler.postDelayed(delayedSearch, delayMs)
        }
    }

    /** 第三方播放器在线取词（可指定来源顺序与是否强制要求翻译）。 */
    private suspend fun fetchThirdPartyLyrics(
        application: Application,
        playerPackage: String,
        baseSong: LocalSong,
        album: String?,
        order: List<Source>,
        requireTranslation: Boolean,
    ): List<LrcLine>? = OnlineLyricTargeter.fetchBestLyric(
        context = application,
        pkgName = playerPackage,
        title = baseSong.name.orEmpty(),
        artist = baseSong.artist.orEmpty(),
        durationMs = baseSong.duration,
        sourceOrder = order,
        requireTranslation = requireTranslation,
        album = album,
    )

    /**
     * LRCLIB 命中歌词但缺翻译时，用四平台补翻译后合并；
     * 若 LRCLIB 基准本身就是罗马音，则自动用在线源原词替换主行，
     * 并保留 LRCLIB 时间戳用于同步；原 LRCLIB 文本降级为 [roma]。
     * 四平台也补不到翻译/原词时，直接用「未命中歌词翻译」报错占位替代歌词展示。
     */
    private suspend fun enrichWithOnlineTranslations(
        application: Application,
        playerPackage: String,
        baseSong: LocalSong,
        album: String?,
        lrclibSong: LocalSong,
        orderedSources: List<Source>,
    ): LocalSong {
        if (orderedSources.isEmpty() || !needsOnlineEnrichment(lrclibSong)) return lrclibSong
        val translationLines = fetchThirdPartyLyrics(
            application = application,
            playerPackage = playerPackage,
            baseSong = baseSong,
            album = album,
            order = orderedSources,
            requireTranslation = true,
        )
        if (translationLines != null) {
            // 自动罗马音替换：检测到 LRCLIB 主行是拉丁罗马音且在线源能拿到非拉丁原词时，
            // 用在线源原词（含译文）按时间戳对齐替换主行，原 LRCLIB 文本降级为 roma，
            // 翻译沿用在线源译文，避免整段翻译被清空；不需要单独开关。
            if (hasRomanizationLines(lrclibSong) &&
                translationLines.any { containsNonLatinLetter(it.content) }
            ) {
                val replaced = replaceRomanizationWithOriginal(lrclibSong, translationLines)
                if (replaced != null) return replaced
                // 罗马音基准与在线源原词对齐失败：直接用在线源行（含原词+翻译）作歌词，
                // 沿用其同步时间戳，避免回落到「罗马音基准 vs 韩文候选」导致翻译整段丢失。
                val fromSource = OnlineFallbackSongMapper.map(lrclibSong, translationLines)
                if (fromSource != null &&
                    fromSource.lyrics.orEmpty().any { !it.translation.isNullOrBlank() }
                ) {
                    return fromSource
                }
            }
            return OnlineTranslationMatcher.apply(lrclibSong, translationLines).song
        }
        // 翻译缺失时仍尝试取原词修复罗马音基准（译文缺失可接受），避免只能看到罗马音
        if (hasRomanizationLines(lrclibSong)) {
            val originalLines = fetchThirdPartyLyrics(
                application = application,
                playerPackage = playerPackage,
                baseSong = baseSong,
                album = album,
                order = orderedSources,
                requireTranslation = false,
            )
            if (originalLines != null && originalLines.any { containsNonLatinLetter(it.content) }) {
                return replaceRomanizationWithOriginal(lrclibSong, originalLines) ?: lrclibSong
            }
        }
        diagnostic("MetaData未命中歌词翻译: title=${baseSong.name}")
        HookLogger.w(
            TAG,
            "MetaData未命中歌词翻译: title=${baseSong.name}, player=$playerPackage",
        )
        return lrclibSong.copy(
            lyrics = null,
            metadata = lyricMetadataOf(
                LyricMetadataKeys.LYRIC_ERROR_MESSAGE to
                    moduleString(
                        R.string.lyric_error_no_translation,
                        "通用插件翻译匹配失败",
                    ),
            ),
        )
    }

    private fun hasRomanizationLines(song: LocalSong): Boolean =
        song.lyrics.orEmpty().any { line ->
            RomanizationPolicy.looksLikeRomanization(line.text.orEmpty())
        }

    private fun containsNonLatinLetter(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (
                Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * 用在线源原词替换 LRCLIB 罗马音主行，保留 LRCLIB 时间戳做同步；
     * 仅替换那些主行确实是拉丁罗马音、且对应在线源行包含非拉丁原词的行。
     */
    private fun replaceRomanizationWithOriginal(
        lrclibSong: LocalSong,
        sourceLines: List<LrcLine>,
    ): LocalSong? {
        val baseLines = lrclibSong.lyrics?.map { line ->
            LrcLine(
                startTimeMs = line.begin,
                content = line.text.orEmpty(),
                translation = line.translation,
                romanization = line.roma,
            )
        } ?: return null
        if (baseLines.isEmpty() || sourceLines.isEmpty()) return null
        val aligned = alignSourceToBase(baseLines, sourceLines)
        val replaced = baseLines.mapIndexed { index, base ->
            val source = aligned.getOrNull(index) ?: base
            val baseIsRomanization = RomanizationPolicy.looksLikeRomanization(base.content)
            val sourceHasOriginal = containsNonLatinLetter(source.content)
            if (baseIsRomanization && sourceHasOriginal && source.content.isNotBlank()) {
                base.copy(
                    content = source.content,
                    translation = source.translation,
                    romanization = base.content,
                )
            } else {
                base
            }
        }
        return OnlineFallbackSongMapper.map(lrclibSong, replaced)
            ?.copy(metadata = lrclibSong.metadata)
    }

    private fun alignSourceToBase(
        baseLines: List<LrcLine>,
        sourceLines: List<LrcLine>,
    ): List<LrcLine> {
        val sortedSource = sourceLines.sortedBy { it.startTimeMs }
        return baseLines.map { base ->
            val best = sortedSource.minByOrNull {
                kotlin.math.abs(it.startTimeMs - base.startTimeMs)
            }
            val distance = best?.let {
                kotlin.math.abs(it.startTimeMs - base.startTimeMs)
            } ?: Long.MAX_VALUE
            if (best != null && distance <= ROMANIZATION_REPLACE_TIME_WINDOW_MS) best else base
        }
    }

    /**
     * 读取模块自身的字符串资源。hook 侧运行在宿主（SystemUI）进程，
     * 宿主 Application 上下文不含模块资源，直接 getString 会抛
     * Resources$NotFoundException 并炸掉 SystemUI 主线程，
     * 因此必须通过 createPackageContext 加载模块包资源，失败时回退到硬编码文案。
     */
    private fun moduleString(resId: Int, fallback: String): String = runCatching {
        val application = app ?: return fallback
        application.createPackageContext(
            BuildConfig.APPLICATION_ID,
            Context.CONTEXT_IGNORE_SECURITY,
        ).resources.getString(resId)
    }.getOrDefault(fallback)

    private fun applyThirdPartyFallbackResult(
        generation: Int,
        baseSong: LocalSong,
        fallbackSong: LocalSong?,
        universalFallback: Boolean = false,
    ) {
        val playerPackage = activeCentralPlayerPackageName
        val song = currentThirdPartySong
        // 单行占位歌词（“歌名 - 歌手”）不算真实歌词，否则兜底结果会被误判过期丢弃。
        val currentHasNoRealLyrics = song == null ||
            song.lyrics.isNullOrEmpty() ||
            isPlaceholderLyrics(song)
        val requestStillCurrent = generation == thirdPartyFallbackGeneration &&
            (universalFallback || isOnlineTranslationEnabledFor(playerPackage)) &&
            song != null && isSameTrack(song, baseSong) &&
            (
                (playerPackage == OnlineTranslationSourcePreferences.SALT_PACKAGE &&
                    isSaltPreferOnlineEnabled()) ||
                currentHasNoRealLyrics
            )
        if (!requestStillCurrent) {
            diagnostic(
                "在线兜底结果已过期: title=${baseSong.name}, " +
                    "generation=$generation, currentGeneration=$thirdPartyFallbackGeneration"
            )
            return
        }
        thirdPartyFallbackJob = null
        if (fallbackSong == null) {
            thirdPartyFallbackSongActive = false
            val missMessage = if (universalFallback) {
                "通用插件 LRCLIB 未命中歌词: title=${baseSong.name}"
            } else {
                "专用插件与通用插件 LRCLIB 均未命中歌词: title=${baseSong.name}"
            }
            diagnostic(missMessage)
            HookLogger.w(
                TAG,
                "$missMessage, player=$activeCentralPlayerPackageName",
            )
            // 未命中任何歌词：把报错信息写入歌曲区域占位，替代“歌名 - 歌手”。
            val errorText = moduleString(
                R.string.lyric_error_no_lyrics,
                "通用插件歌词匹配失败",
            )
            val errorSong = baseSong.copy(
                lyrics = null,
                metadata = lyricMetadataOf(
                    LyricMetadataKeys.LYRIC_ERROR_MESSAGE to errorText,
                ),
            )
            thirdPartyMatchFailedIdentity = songIdentity(baseSong)
            currentPublishedThirdPartySong = errorSong
            publishSong(errorSong, restorePosition = true)
            return
        }
        thirdPartyFallbackSongActive = true
        thirdPartyMatchFailedIdentity = null
        currentPublishedThirdPartySong = fallbackSong
        HookLogger.i(
            TAG,
            "在线兜底命中: title=${baseSong.name}, " +
                "lines=${fallbackSong.lyrics.orEmpty().size}, " +
                "translations=${fallbackSong.lyrics.orEmpty().count {
                    OnlineTranslationContentPolicy.isMeaningful(it.translation)
                }}"
        )
        publishSong(fallbackSong, restorePosition = true)
    }

    private fun cancelThirdPartyFallback(reason: String) {
        if (thirdPartyFallbackDelayRunnable != null ||
            thirdPartyFallbackJob?.isActive == true ||
            thirdPartyFallbackSongActive
        ) {
            diagnostic(
                "在线兜底取消: reason=$reason, " +
                    "title=${currentThirdPartySong?.name}"
            )
        }
        thirdPartyFallbackGeneration += 1
        thirdPartyFallbackDelayRunnable?.let(mainHandler::removeCallbacks)
        thirdPartyFallbackDelayRunnable = null
        thirdPartyFallbackJob?.cancel()
        thirdPartyFallbackJob = null
        thirdPartyFallbackSongActive = false
    }

    private fun scheduleOnlineTranslation(baseSong: LocalSong): Boolean {
        val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
        val effectivePlayerPackage = activeCentralPlayerPackageName ?: APPLE_MUSIC_PACKAGE
        val matchingEnabled = if (effectivePlayerPackage == APPLE_MUSIC_PACKAGE) {
            isAppleTranslationEnrichmentEnabled()
        } else {
            isThirdPartyOnlineEnabledFor(effectivePlayerPackage)
        }
        val nativeLineCount = baseSong.lyrics.orEmpty().size
        val enrichmentNeeded = needsOnlineEnrichment(baseSong)
        val titlePresent = !baseSong.name.isNullOrBlank()
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_schedule_evaluated",
            details = "matchingEnabled=$matchingEnabled,nativeLines=$nativeLineCount," +
                "enrichmentNeeded=$enrichmentNeeded,titlePresent=$titlePresent," +
                "generation=$onlineTranslationGeneration",
        )
        if (!matchingEnabled || nativeLineCount == 0 || !enrichmentNeeded || !titlePresent) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_schedule_skipped",
                details = "reason=precondition_failed",
            )
            pronunciationDiagnostic(
                "stage=request_schedule_skipped, reason=precondition_failed, " +
                    "id=${baseSong.id}, prefsPresent=${prefs != null}, " +
                    "matchingEnabled=$matchingEnabled, nativeLines=$nativeLineCount, " +
                    "needsEnrichment=$enrichmentNeeded, titlePresent=$titlePresent"
            )
            return false
        }
        val attemptKey = translationIdentity(baseSong)
        if (onlineTranslationAttemptKey == attemptKey) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_schedule_skipped",
                details = "reason=attempt_already_recorded",
            )
            pronunciationDiagnostic(
                "stage=request_schedule_skipped, reason=attempt_already_recorded, " +
                    "id=${baseSong.id}, attempt=$attemptKey"
            )
            return false
        }

        onlineTranslationAttemptKey = attemptKey
        onlineTranslationGeneration += 1
        val generation = onlineTranslationGeneration
        onlineRaceFirstPublishedGeneration = null
        onlineRaceFirstAcceptedGeneration = null
        pendingOnlineTranslationCommit = null
        val previousJobActive = onlineTranslationJob?.isActive == true
        pronunciationDiagnostic(
            "stage=request_scheduled, generation=$generation, id=${baseSong.id}, " +
                "attempt=$attemptKey, nativeLines=${baseSong.lyrics.orEmpty().size}"
        )
        onlineTranslationJob?.cancel()
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_job_scheduled",
            details = "generation=$generation,cancelledJobActive=$previousJobActive",
        )

        fun postTranslationApply(
            selection: OnlineTranslationSelection?,
            publicationStage: OnlineTranslationPublicationStage,
            reason: String,
        ) {
            val postedAtNanos = SystemClock.elapsedRealtimeNanos()
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_apply_posted",
                details = "generation=$generation,publicationStage=$publicationStage," +
                    "reason=$reason,resultPresent=${selection != null}",
            )
            mainHandler.post {
                val applyStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_apply_main_started",
                    details = "generation=$generation,publicationStage=$publicationStage," +
                        "queueWaitMs=" +
                        ((applyStartedAtNanos - postedAtNanos) / 1_000_000.0),
                )
                try {
                    applyOnlineTranslationResult(
                        generation = generation,
                        baseSong = baseSong,
                        selection = selection,
                        publicationStage = publicationStage,
                    )
                } finally {
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "translation_apply_main_finished",
                        details = "generation=$generation,publicationStage=$publicationStage," +
                            "elapsedMs=" +
                            ((SystemClock.elapsedRealtimeNanos() - applyStartedAtNanos) /
                                1_000_000.0),
                    )
                }
            }
        }

        onlineTranslationJob = fallbackScope.launch {
            val mutexWaitStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_mutex_wait_started",
                details = "generation=$generation",
            )
            try {
                fallbackRequestMutex.withLock {
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "translation_mutex_acquired",
                        details = "generation=$generation,waitMs=" +
                            ((SystemClock.elapsedRealtimeNanos() - mutexWaitStartedAtNanos) /
                                1_000_000.0),
                    )
                    if (generation != onlineTranslationGeneration) {
                        sourceSwitchCoreStage(
                            request = sourceSwitchRequest,
                            stage = "translation_search_abandoned",
                            details = "generation=$generation," +
                                "currentGeneration=$onlineTranslationGeneration",
                        )
                        pronunciationDiagnostic(
                            "stage=request_abandoned, generation=$generation, id=${baseSong.id}, " +
                                "reason=generation_changed_before_start, " +
                                "currentGeneration=$onlineTranslationGeneration"
                        )
                        return@withLock
                    }
                    val application = app ?: run {
                        pronunciationDiagnostic(
                            "stage=request_abandoned, generation=$generation, id=${baseSong.id}, " +
                                "reason=application_unavailable"
                        )
                        return@withLock
                    }
                    val configuredSources = configuredOnlineSources()
                    if (configuredSources.isEmpty()) {
                        postTranslationApply(
                            selection = null,
                            publicationStage = OnlineTranslationPublicationStage.SINGLE,
                            reason = "no_configured_sources",
                        )
                        return@withLock
                    }
                    val automaticSelection =
                        OnlineTranslationSourcePreferences.isAutoSelectBestSourceEnabled(prefs)
                    val preferredSource = configuredSources.first()
                    val alternativeSource = configuredSources.drop(1).firstOrNull()
                    val requestedFirstSource =
                        pendingTranslationSourceRequest?.requestedSource
                            ?: pendingPronunciationSourceRequest?.requestedSource
                    val raceEnabled = automaticSelection && requestedFirstSource == null &&
                        temporaryTranslationSource == null && temporaryPronunciationSource == null
                    val firstSource = requestedFirstSource
                        ?.takeIf(configuredSources::contains)
                        ?: preferredSource
                    val remainingSources = listOf(firstSource) + configuredSources.filterNot {
                        it == firstSource
                    }
                    val playerPackage = activeCentralPlayerPackageName ?: APPLE_MUSIC_PACKAGE
                    val completeOnlinePronunciation = playerPackage == APPLE_MUSIC_PACKAGE &&
                        ApplePronunciationVisibilityPolicy.allowsOnlineSupplementation(
                            song = baseSong,
                            hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                        )
                    val totalLineCount = baseSong.lyrics.orEmpty().sumOf { line ->
                        if (line.text.isNullOrBlank()) {
                            0
                        } else {
                            (if (!OnlineTranslationContentPolicy.isMeaningful(line.translation)) 1 else 0) +
                            (if (
                                completeOnlinePronunciation && line.roma.isNullOrBlank()
                            ) 1 else 0)
                        }
                    }
                    val mediaInfo = MediaMetadataHelper.getMediaInfo(application, playerPackage)
                    val searchDuration = if (playerPackage == APPLE_MUSIC_PACKAGE) {
                        AppleOnlineTranslationSearchDurationPolicy.resolve(
                            song = baseSong,
                            media = AppleOnlineTranslationSearchDurationPolicy.MediaSnapshot(
                                title = mediaInfo.title,
                                artist = mediaInfo.artist,
                                durationMs = mediaInfo.duration,
                            ),
                        )
                    } else {
                        AppleOnlineTranslationSearchDurationPolicy.Resolution(
                            durationMs = baseSong.duration.takeIf { it > 0L }
                                ?: mediaInfo.duration,
                            mediaIdentityMatched = false,
                        )
                    }
                    diagnostic(
                        "在线歌词补全开始: player=$playerPackage, title=${baseSong.name}, " +
                            "artist=${baseSong.artist}, preferred=$preferredSource, " +
                            "first=$firstSource, requested=${requestedFirstSource ?: "none"}"
                    )
                    pronunciationDiagnostic(
                        "stage=search_duration_resolved, generation=$generation, id=${baseSong.id}, " +
                            "lyricDuration=${baseSong.duration}, mediaDuration=${mediaInfo.duration}, " +
                            "mediaTitle=${mediaInfo.title}, mediaArtist=${mediaInfo.artist}, " +
                            "identityMatched=${searchDuration.mediaIdentityMatched}, " +
                            "selectedDuration=${searchDuration.durationMs}"
                    )
                    pronunciationDiagnostic(
                        "stage=request_started, generation=$generation, id=${baseSong.id}, " +
                            "preferred=$preferredSource, order=${remainingSources.joinToString("+")}, " +
                            "pronunciationAllowed=$completeOnlinePronunciation, " +
                        "targetContent=$totalLineCount"
                    )
                    val searchStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "translation_search_started",
                        details = "generation=$generation,order=${remainingSources.joinToString("+")}," +
                            "race=$raceEnabled,targetContent=$totalLineCount",
                    )
                    val fetchedCandidates = linkedMapOf<Source, OnlineTranslationSelector.Candidate>()
                    if (raceEnabled && remainingSources.size > 1) {
                        pronunciationDiagnostic(
                            "stage=race_started, generation=$generation, id=${baseSong.id}, " +
                                "sources=${remainingSources.joinToString("+")}"
                        )
                        fetchedCandidates += OnlineTranslationRace.run(
                            sources = remainingSources,
                            clockMs = SystemClock::elapsedRealtime,
                            fetch = { source ->
                                fetchOnlineTranslationCandidate(
                                    application = application,
                                    baseSong = baseSong,
                                    source = source,
                                    totalLineCount = totalLineCount,
                                    generation = generation,
                                    searchDurationMs = searchDuration.durationMs,
                                )
                            },
                            onCompletion = { completion ->
                                val source = completion.source
                                val candidate = completion.value
                                pronunciationDiagnostic(
                                    "stage=race_source_finished, generation=$generation, " +
                                        "id=${baseSong.id}, source=$source, elapsedMs=${completion.elapsedMs}, " +
                                        "found=${candidate != null}, error=${completion.error?.javaClass?.name ?: "none"}"
                                )
                                completion.error?.let { error ->
                                    debugError(
                                        "在线翻译赛马来源失败: title=${baseSong.name}, source=$source",
                                        error,
                                    )
                                }
                                if (candidate != null) {
                                    if (onlineRaceFirstPublishedGeneration != generation &&
                                        candidate.matchedContentCount > 0
                                    ) {
                                        val firstSelection = OnlineTranslationSelection(
                                            onlineLinesBySource = mapOf(source to candidate.onlineLines),
                                            requestedSources = listOf(source),
                                            defaultTranslationSource = source.takeIf {
                                                OnlineTranslationMatcher.contributesTranslation(
                                                    baseSong,
                                                    candidate.result,
                                                )
                                            },
                                            defaultPronunciationSource = source.takeIf {
                                                OnlineTranslationMatcher.contributesPronunciation(
                                                    baseSong,
                                                    candidate.result,
                                                )
                                            },
                                            sourceOrder = listOf(source),
                                            pronunciationRequested = completeOnlinePronunciation,
                                        )
                                        onlineRaceFirstPublishedGeneration = generation
                                        pronunciationDiagnostic(
                                            "stage=race_first_ready, generation=$generation, " +
                                                "id=${baseSong.id}, source=$source, " +
                                                "matchedContent=${candidate.matchedContentCount}"
                                        )
                                        postTranslationApply(
                                            selection = firstSelection,
                                            publicationStage =
                                                OnlineTranslationPublicationStage.RACE_FIRST,
                                            reason = "race_first_ready",
                                        )
                                    }
                                }
                            },
                        )
                    } else {
                        remainingSources.forEachIndexed { index, source ->
                            val previous = fetchedCandidates.values.lastOrNull()
                            val explicitlyRequested = temporaryTranslationSource == source ||
                                temporaryPronunciationSource == source
                            if (index > 0 && !automaticSelection && !explicitlyRequested &&
                                !OnlineTranslationSelector.shouldTryAlternative(previous, totalLineCount)
                            ) {
                                return@forEachIndexed
                            }
                            if (index > 0) {
                                diagnostic(
                                    "在线翻译继续尝试后续来源: " +
                                        "title=${baseSong.name}, source=$source"
                                )
                            }
                            fetchOnlineTranslationCandidate(
                                application = application,
                                baseSong = baseSong,
                                source = source,
                                totalLineCount = totalLineCount,
                                generation = generation,
                                searchDurationMs = searchDuration.durationMs,
                            )?.let { fetchedCandidates[source] = it }
                        }
                    }
                    val candidates = fetchedCandidates
                    val rankedCandidates = if (automaticSelection) {
                        OnlineTranslationSelector.rank(
                            candidates = candidates.values,
                            totalLineCount = totalLineCount,
                            tieBreakOrder = OnlineTranslationSourcePreferences.defaultOrder,
                        )
                    } else {
                        val preferredCandidate = candidates[preferredSource]
                        val alternativeCandidate = alternativeSource?.let(candidates::get)
                        val selectedCandidate = OnlineTranslationSelector.select(
                            preferred = preferredCandidate,
                            alternative = alternativeCandidate,
                            totalLineCount = totalLineCount,
                        )
                        buildList {
                            selectedCandidate?.let(::add)
                            listOfNotNull(preferredCandidate, alternativeCandidate)
                                .filterNot(::contains)
                                .forEach(::add)
                            candidates.values.filterNot(::contains).forEach(::add)
                        }
                    }
                    val selected = rankedCandidates.firstOrNull()
                    val supplementalCandidates = rankedCandidates.drop(1)
                    val defaultTranslationCandidate = rankedCandidates
                        .firstOrNull {
                            OnlineTranslationMatcher.contributesTranslation(baseSong, it.result)
                        }
                    val defaultPronunciationCandidate = rankedCandidates
                        .firstOrNull {
                            OnlineTranslationMatcher.contributesPronunciation(baseSong, it.result)
                        }
                    val selection = OnlineTranslationSelection(
                        onlineLinesBySource = candidates.mapValues { it.value.onlineLines },
                        requestedSources = remainingSources,
                        defaultTranslationSource = defaultTranslationCandidate?.source,
                        defaultPronunciationSource = defaultPronunciationCandidate?.source,
                        forcedTranslationSource = temporaryTranslationSource,
                        forcedPronunciationSource = temporaryPronunciationSource,
                        sourceOrder = if (automaticSelection) {
                            rankedCandidates.map { it.source }
                        } else {
                            configuredSources
                        },
                        pronunciationRequested = completeOnlinePronunciation,
                    )
                    val currentPublishedSong = (if (playerPackage == APPLE_MUSIC_PACKAGE) {
                        currentPublishedAppleSong
                    } else {
                        currentPublishedThirdPartySong
                    })
                        ?.takeIf { isSameTrack(it, baseSong) }
                        ?.let { publishedSong ->
                            ApplePronunciationVisibilityPolicy.filterSong(
                                song = publishedSong,
                                hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                            )
                        }
                    val mergedResult = selection.compose(baseSong, currentPublishedSong)
                    val selectedTranslationSource = mergedResult
                        ?.song
                        ?.metadata
                        ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
                    val selectedPronunciationSource = mergedResult
                        ?.song
                        ?.metadata
                        ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
                    diagnostic(
                        "在线翻译来源选择: player=$playerPackage, title=${baseSong.name}, " +
                        "preferred=$preferredSource, selected=${selected?.source}, " +
                        "translation=$selectedTranslationSource, " +
                        "pronunciation=$selectedPronunciationSource, " +
                        "compared=${rankedCandidates.size > 1}, automatic=$automaticSelection"
                    )
                    HookLogger.i(
                        TAG,
                        "在线翻译来源选择: player=$playerPackage, title=${baseSong.name}, " +
                            "selected=${selected?.source}, " +
                            "translation=$selectedTranslationSource, " +
                            "pronunciation=$selectedPronunciationSource, " +
                            "compared=${rankedCandidates.size > 1}, automatic=$automaticSelection"
                    )
                    val selectedMatchedCount = selected?.result?.matchedCount ?: 0
                    if (mergedResult != null && mergedResult.matchedCount > selectedMatchedCount) {
                        diagnostic(
                            "在线翻译已由后续来源补齐: player=$playerPackage, title=${baseSong.name}, " +
                                "source=${supplementalCandidates.joinToString("+") { it.source.name }}, " +
                                "matched=$selectedMatchedCount->${mergedResult.matchedCount}"
                        )
                    }
                    pronunciationDiagnostic(
                        "stage=selection_composed, generation=$generation, id=${baseSong.id}, " +
                            "candidateSources=${candidates.keys.joinToString("+")}, " +
                            "selected=${selected?.source}, translationSource=$selectedTranslationSource, " +
                            "pronunciationSource=$selectedPronunciationSource, " +
                            "resultPresent=${mergedResult != null}, " +
                            "resultRomanized=${mergedResult?.song?.lyrics.orEmpty().count { !it.roma.isNullOrBlank() }}"
                    )
                    if (raceEnabled) {
                        pronunciationDiagnostic(
                            "stage=race_finished, generation=$generation, id=${baseSong.id}, " +
                                "ranking=${rankedCandidates.joinToString(",") { candidate ->
                                    "${candidate.source}:${formatMetric(OnlineTranslationSelector.quality(candidate, totalLineCount))}"
                                }}"
                        )
                    }
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "translation_search_finished",
                        details = "generation=$generation,elapsedMs=" +
                            ((SystemClock.elapsedRealtimeNanos() - searchStartedAtNanos) /
                                1_000_000.0) +
                            ",candidates=${candidates.keys.joinToString("+")}," +
                            "selected=${selected?.source ?: "none"}",
                    )
                    postTranslationApply(
                        selection = selection,
                        publicationStage = if (raceEnabled) {
                            OnlineTranslationPublicationStage.RACE_FINAL
                        } else {
                            OnlineTranslationPublicationStage.SINGLE
                        },
                        reason = "search_finished",
                    )
                }
            } catch (e: CancellationException) {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_job_cancelled",
                    details = "generation=$generation," +
                        "currentGeneration=$onlineTranslationGeneration",
                )
                pronunciationDiagnostic(
                    "stage=request_cancelled, generation=$generation, id=${baseSong.id}, " +
                        "currentGeneration=$onlineTranslationGeneration"
                )
                throw e
            } catch (e: Exception) {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_job_failed",
                    details = "generation=$generation,error=${e.javaClass.simpleName}",
                )
                pronunciationDiagnostic(
                    "stage=request_failed, generation=$generation, id=${baseSong.id}, " +
                        "error=${e.javaClass.name}, message=${e.message}"
                )
                postTranslationApply(
                    selection = null,
                    publicationStage = OnlineTranslationPublicationStage.SINGLE,
                    reason = "search_failed",
                )
                debugError(
                    "在线翻译匹配失败: player=$activeCentralPlayerPackageName, " +
                        "title=${baseSong.name}",
                    e,
                )
            }
        }
        return true
    }

    private suspend fun fetchOnlineTranslationCandidate(
        application: Application,
        baseSong: LocalSong,
        source: Source,
        totalLineCount: Int,
        generation: Int,
        searchDurationMs: Long,
    ): OnlineTranslationSelector.Candidate? {
        val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
        val fetchStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_source_fetch_started",
            details = "generation=$generation,source=$source,durationMs=$searchDurationMs",
        )
        pronunciationDiagnostic(
            "stage=candidate_fetch_started, generation=$generation, id=${baseSong.id}, source=$source"
        )
        val mediaInfo = MediaMetadataHelper.getMediaInfo(application, APPLE_MUSIC_PACKAGE, HookLogger)
        val originalAlbum = baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ALBUM)
            ?.takeIf(String::isNotBlank)
        val albumForSearch = originalAlbum ?: mediaInfo.album
        val fetchOutcome = OnlineLyricTargeter.fetchBestLyricWithNearMiss(
            context = application,
            pkgName = APPLE_MUSIC_PACKAGE,
            title = baseSong.name.orEmpty(),
            artist = baseSong.artist.orEmpty(),
            durationMs = searchDurationMs,
            originalTitle = baseSong.metadata
                ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
            originalArtist = baseSong.metadata
                ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST),
            preferOriginalMetadata = shouldPreferAppleOriginalMetadata(),
            preferredSource = source,
            requireTranslation = false,
            fallbackToOtherSources = false,
            album = albumForSearch,
            originalAlbum = originalAlbum,
        )
        val onlineLines = fetchOutcome.lines ?: fetchOutcome.nearMiss?.lines
        if (onlineLines == null) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_source_fetch_finished",
                details = "generation=$generation,source=$source,elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - fetchStartedAtNanos) /
                        1_000_000.0) +
                    ",found=false",
            )
            pronunciationDiagnostic(
                "stage=candidate_fetch_finished, generation=$generation, id=${baseSong.id}, " +
                    "source=$source, found=false"
            )
            diagnostic(
                "在线翻译候选未命中: player=$activeCentralPlayerPackageName, " +
                    "title=${baseSong.name}, source=$source"
            )
            return null
        }
        val appleRequest = activeCentralPlayerPackageName == APPLE_MUSIC_PACKAGE ||
            currentThirdPartySong == null
        val filteredOnlineLines = ApplePronunciationVisibilityPolicy.filterOnlineLines(
            song = baseSong,
            onlineLines = onlineLines,
            hideMandarinPinyin = appleRequest && isHideMandarinPinyinEnabled(),
        ).let { lines ->
            if (appleRequest) lines else lines.map { it.copy(romanization = null) }
        }
        val result = OnlineTranslationMatcher.apply(baseSong, filteredOnlineLines)
        val baseLines = baseSong.lyrics.orEmpty()
        val enrichedLines = result.song.lyrics.orEmpty()
        val matchedTranslationCount = baseLines.indices.count { index ->
            !OnlineTranslationContentPolicy.isMeaningful(baseLines[index].translation) &&
                OnlineTranslationContentPolicy.isMeaningful(
                    enrichedLines.getOrNull(index)?.translation
                )
        }
        val matchedPronunciationCount = baseLines.indices.count { index ->
            baseLines[index].roma.isNullOrBlank() &&
                !enrichedLines.getOrNull(index)?.roma.isNullOrBlank()
        }
        if (fetchOutcome.nearMiss != null) {
            val verification = AppleOnlineTranslationNearMissPolicy.VerificationInputs(
                score = fetchOutcome.nearMiss.score,
                missingTranslationCount = baseLines.count {
                    !OnlineTranslationContentPolicy.isMeaningful(it.translation)
                },
                matchedTranslationCount = matchedTranslationCount,
                missingPronunciationCount = baseLines.count { it.roma.isNullOrBlank() },
                matchedPronunciationCount = matchedPronunciationCount,
                averageMatchScore = result.averageMatchScore,
                durationVerified = fetchOutcome.nearMiss.durationVerified,
            )
            val coverage = AppleOnlineTranslationNearMissPolicy.contentCoverage(verification)
            if (!AppleOnlineTranslationNearMissPolicy.accepts(verification)) {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_source_fetch_finished",
                    details = "generation=$generation,source=$source,elapsedMs=" +
                        ((SystemClock.elapsedRealtimeNanos() - fetchStartedAtNanos) /
                            1_000_000.0) +
                        ",found=false,reason=near_miss_rejected," +
                        "matchedTranslation=$matchedTranslationCount," +
                        "matchedPronunciation=$matchedPronunciationCount",
                )
                pronunciationDiagnostic(
                    "stage=near_miss_rejected, generation=$generation, id=${baseSong.id}, " +
                        "source=$source, score=${fetchOutcome.nearMiss.score}, " +
                        "coverage=${formatMetric(coverage)}, " +
                        "confidence=${formatMetric(result.averageMatchScore)}, " +
                        "matchedTranslation=$matchedTranslationCount, " +
                        "matchedPronunciation=$matchedPronunciationCount, " +
                        "durationVerified=${fetchOutcome.nearMiss.durationVerified}"
                )
                diagnostic(
                    "在线翻译近失候选未通过歌词重叠校验: " +
                        "player=$activeCentralPlayerPackageName, " +
                        "title=${baseSong.name}, source=$source, " +
                        "score=${fetchOutcome.nearMiss.score}, coverage=${formatMetric(coverage)}"
                )
                return null
            }
            pronunciationDiagnostic(
                "stage=near_miss_accepted, generation=$generation, id=${baseSong.id}, " +
                    "source=$source, score=${fetchOutcome.nearMiss.score}, " +
                    "coverage=${formatMetric(coverage)}, " +
                    "confidence=${formatMetric(result.averageMatchScore)}, " +
                    "matchedTranslation=$matchedTranslationCount, " +
                    "matchedPronunciation=$matchedPronunciationCount, " +
                    "durationVerified=${fetchOutcome.nearMiss.durationVerified}"
            )
        }
        val candidate = OnlineTranslationSelector.Candidate(
            source = source,
            onlineLineCount = onlineLines.size,
            translatedLineCount = onlineLines.count {
                OnlineTranslationContentPolicy.isMeaningful(it.translation)
            },
            result = result,
            romanizedLineCount = filteredOnlineLines.count {
                !it.romanization.isNullOrBlank()
            },
            matchedContentCount = matchedTranslationCount + matchedPronunciationCount,
            onlineLines = filteredOnlineLines,
        )
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_source_fetch_finished",
            details = "generation=$generation,source=$source,elapsedMs=" +
                ((SystemClock.elapsedRealtimeNanos() - fetchStartedAtNanos) /
                    1_000_000.0) +
                ",found=true,rawLines=${onlineLines.size}," +
                "matchedTranslation=$matchedTranslationCount," +
                "matchedPronunciation=$matchedPronunciationCount",
        )
        pronunciationDiagnostic(
            "stage=candidate_fetch_finished, generation=$generation, id=${baseSong.id}, " +
                "source=$source, found=true, rawLines=${onlineLines.size}, " +
                "rawRomanized=${onlineLines.count { !it.romanization.isNullOrBlank() }}, " +
                "filteredLines=${filteredOnlineLines.size}, " +
                "filteredRomanized=${candidate.romanizedLineCount}"
        )
        pronunciationDiagnostic(
            "stage=matcher_finished, generation=$generation, id=${baseSong.id}, source=$source, " +
                "matchedLines=${result.matchedCount}, matchedTranslation=$matchedTranslationCount, " +
                "matchedPronunciation=$matchedPronunciationCount, " +
                "resultRomanized=${enrichedLines.count { !it.roma.isNullOrBlank() }}"
        )
        diagnostic(
            "在线翻译候选: player=$activeCentralPlayerPackageName, " +
                "title=${baseSong.name}, source=$source, " +
                "lines=${candidate.onlineLineCount}, " +
                "translated=${candidate.translatedLineCount}, " +
                "romanized=${candidate.romanizedLineCount}, " +
                "matchedLines=${result.matchedCount}, " +
                "matchedContent=${candidate.matchedContentCount}/$totalLineCount, " +
                "coverage=${formatMetric(OnlineTranslationSelector.coverage(candidate, totalLineCount))}, " +
                "confidence=${formatMetric(result.averageMatchScore)}, " +
                "quality=${formatMetric(OnlineTranslationSelector.quality(candidate, totalLineCount))}"
        )
        return candidate
    }

    private fun formatMetric(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    private fun applyOnlineTranslationResult(
        generation: Int,
        baseSong: LocalSong,
        selection: OnlineTranslationSelection?,
        publicationStage: OnlineTranslationPublicationStage = OnlineTranslationPublicationStage.SINGLE,
    ) {
        val appleRequest = activeCentralPlayerPackageName == APPLE_MUSIC_PACKAGE ||
            currentThirdPartySong == null
        val nativeSong = if (appleRequest) currentAppleSong else currentThirdPartySong
        val generationMatches = generation == onlineTranslationGeneration
        val sameTrack = nativeSong != null && isSameTrack(nativeSong, baseSong)
        val nativeLyricsAvailable = if (appleRequest) {
            hasAppleLyricsForOnlineEnrichment(
                song = nativeSong,
                confirmedNativeLyrics = currentAppleHasNativeLyrics,
            )
        } else {
            !nativeSong?.lyrics.isNullOrEmpty()
        }
        val enrichmentNeeded = needsOnlineEnrichment(nativeSong)
        val matchingEnabled = if (appleRequest) {
            isAppleTranslationEnrichmentEnabled()
        } else {
            isThirdPartyOnlineEnabledFor(activeCentralPlayerPackageName)
        }
        val overlayPublicationEnabled = !appleRequest ||
            isOnlineTranslationEnabledFor(APPLE_MUSIC_PACKAGE)
        val requestStillCurrent = generationMatches && nativeSong != null && sameTrack &&
            nativeLyricsAvailable && enrichmentNeeded && matchingEnabled
        pronunciationDiagnostic(
            "stage=apply_guard, generation=$generation, id=${baseSong.id}, " +
                "accepted=$requestStillCurrent, currentGeneration=$onlineTranslationGeneration, " +
                "generationMatches=$generationMatches, sameTrack=$sameTrack, " +
                "nativeLyrics=$nativeLyricsAvailable, enrichmentNeeded=$enrichmentNeeded, " +
                "matchingEnabled=$matchingEnabled, resultPresent=${selection != null}, " +
                "candidateSources=${selection?.onlineLinesBySource?.keys?.joinToString("+").orEmpty()}"
        )
        if (!requestStillCurrent) {
            diagnostic(
                "在线翻译匹配结果已过期: player=$activeCentralPlayerPackageName, " +
                    "title=${baseSong.name}, " +
                    "generation=$generation, currentGeneration=$onlineTranslationGeneration"
            )
            return
        }

        if (publicationStage != OnlineTranslationPublicationStage.RACE_FIRST) {
            onlineTranslationJob = null
        }
        val latestNativeSong = nativeSong
        val currentPublishedSong = (if (appleRequest) {
            currentPublishedAppleSong
        } else {
            currentPublishedThirdPartySong
        })
            ?.takeIf { isSameTrack(it, latestNativeSong) }
            ?.let { publishedSong ->
                ApplePronunciationVisibilityPolicy.filterSong(
                    song = publishedSong,
                    hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                )
            }
        val nativeLyricsChangedDuringRequest = baseSong.lyrics != latestNativeSong.lyrics
        val mergedResult = (selection ?: OnlineTranslationSelection()).compose(
            latestNativeSong = latestNativeSong,
            currentPublishedSong = currentPublishedSong,
        )
        val candidateResults = selection
            ?.matchCandidates(latestNativeSong)
            .orEmpty()
        pronunciationDiagnostic(
            "stage=result_rebased, generation=$generation, id=${baseSong.id}, " +
                "nativeLyricsChanged=$nativeLyricsChangedDuringRequest, " +
                "requestLines=${baseSong.lyrics.orEmpty().size}, " +
                "latestLines=${latestNativeSong.lyrics.orEmpty().size}, " +
                "candidateSources=${selection?.onlineLinesBySource?.keys?.joinToString("+").orEmpty()}"
        )
        val hasPendingSourceSwitch = pendingTranslationSourceRequest
            ?.takeIf { it.songId == baseSong.id } != null ||
            pendingPronunciationSourceRequest
                ?.takeIf { it.songId == baseSong.id } != null
        val hasOnlineEnrichment = mergedResult != null &&
            (
                OnlineTranslationMatcher.contributesTranslation(latestNativeSong, mergedResult) ||
                    OnlineTranslationMatcher.contributesPronunciation(
                        latestNativeSong,
                        mergedResult,
                    )
                )
        val mergedRomanizedLines = mergedResult?.song?.lyrics.orEmpty().count {
            !it.roma.isNullOrBlank()
        }
        pronunciationDiagnostic(
            "stage=apply_decision, generation=$generation, id=${baseSong.id}, " +
                "mergedPresent=${mergedResult != null}, mergedRomanized=$mergedRomanizedLines, " +
                "hasOnlineEnrichment=$hasOnlineEnrichment, sourceSwitch=$hasPendingSourceSwitch"
        )
        if (mergedResult == null || (!hasOnlineEnrichment && !hasPendingSourceSwitch)) {
            diagnostic(
                "在线翻译匹配未命中: player=$activeCentralPlayerPackageName, " +
                    "title=${baseSong.name}"
            )
            HookLogger.i(
                TAG,
                "在线翻译匹配未命中: player=$activeCentralPlayerPackageName, " +
                    "title=${baseSong.name}"
            )
            if (appleRequest && requestOriginalMetadata(baseSong, "translation_match_miss")) return
            completePendingOnlineSourceSwitchRequests(null)
            if (!onlineMatchedTranslationActive && overlayPublicationEnabled) {
                if (
                    !currentPublishedAppleOnlineTranslationMatched &&
                    (if (appleRequest) currentPublishedAppleSong else currentPublishedThirdPartySong) !=
                        latestNativeSong
                ) {
                    if (appleRequest) publishAppleSong(latestNativeSong, restorePosition = true)
                    else {
                        currentPublishedThirdPartySong = latestNativeSong
                        publishSong(latestNativeSong, restorePosition = true)
                    }
                }
                sink?.onOnlineTranslationUnavailable(nativeSong)
            }
            return
        }

        onlineMatchedTranslationActive = hasOnlineEnrichment
        if (
            publicationStage == OnlineTranslationPublicationStage.RACE_FIRST &&
            hasOnlineEnrichment
        ) {
            onlineRaceFirstAcceptedGeneration = generation
        }
        diagnostic(
            "在线翻译结果接受: player=$activeCentralPlayerPackageName, " +
                "title=${baseSong.name}, " +
                "matched=${mergedResult.matchedCount}, enriched=$hasOnlineEnrichment, " +
                "sourceSwitch=$hasPendingSourceSwitch, total=${baseSong.lyrics.orEmpty().size}"
        )
        HookLogger.i(
            TAG,
            "在线翻译结果接受: player=$activeCentralPlayerPackageName, " +
                "title=${baseSong.name}, " +
                "matched=${mergedResult.matchedCount}, enriched=$hasOnlineEnrichment, " +
                "sourceSwitch=$hasPendingSourceSwitch, total=${baseSong.lyrics.orEmpty().size}"
        )
        val unmatched = mergedResult.song.lyrics.orEmpty().mapIndexedNotNull { index, line ->
            val missing = buildList {
                if (!OnlineTranslationContentPolicy.isMeaningful(line.translation)) {
                    add("translation")
                }
                if (line.roma.isNullOrBlank()) add("pronunciation")
            }
            missing.takeIf { it.isNotEmpty() }?.let {
                "$index@${line.begin}[${it.joinToString("+")}]:${line.text.orEmpty().take(48)}"
            }
        }
        if (unmatched.isNotEmpty()) {
            diagnostic(
                "在线翻译未匹配行: player=$activeCentralPlayerPackageName, " +
                    "title=${baseSong.name}, " +
                    unmatched.joinToString(separator = " | ")
                )
        }
        if (BuildConfig.DEBUG && selection != null) {
            val contributions = OnlineTranslationDiagnostics.contributions(
                baseSong = latestNativeSong,
                resultSong = mergedResult.song,
                candidates = candidateResults,
                translationOrder = actualSourceFirst(
                    mergedResult.song.metadata
                        ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE),
                    selection.sourceOrder.ifEmpty { selection.requestedSources },
                ),
                pronunciationOrder = actualSourceFirst(
                    mergedResult.song.metadata
                        ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE),
                    selection.sourceOrder.ifEmpty { selection.requestedSources },
                ),
            )
            if (contributions.isNotEmpty()) {
                pronunciationDiagnostic(
                    "stage=line_contributions, generation=$generation, id=${baseSong.id}, " +
                        contributions.joinToString("|") { contribution ->
                            "line=${contribution.index}@${contribution.begin}, " +
                                "translation=${contribution.translationSource ?: "none"}, " +
                                "background=${contribution.backgroundTranslationSource ?: "none"}, " +
                                "pronunciation=${contribution.pronunciationSource ?: "none"}"
                        }
                )
            }
            val missingLines = OnlineTranslationDiagnostics.missingLines(
                resultSong = mergedResult.song,
                requestedSources = selection.requestedSources.ifEmpty { selection.sourceOrder },
                onlineLinesBySource = selection.onlineLinesBySource,
                candidates = candidateResults,
                pronunciationRequested = selection.pronunciationRequested,
            )
            if (missingLines.isNotEmpty()) {
                pronunciationDiagnostic(
                    "stage=line_missing_diagnostics, generation=$generation, id=${baseSong.id}, " +
                        missingLines.joinToString("|") { missing ->
                            "line=${missing.index}@${missing.begin}, missing=${missing.missing.joinToString("+")}, " +
                                "reasons=${missing.reasonsBySource.entries.joinToString(",") { (source, reasons) ->
                                    "$source:${reasons.joinToString("+")}"
                                }}"
                        }
                )
            }
        }
        if (
            publicationStage == OnlineTranslationPublicationStage.RACE_FINAL &&
            onlineRaceFirstAcceptedGeneration == generation &&
            selection != null &&
            currentPublishedSong != null &&
            !sameOnlineTranslationContent(currentPublishedSong, mergedResult.song)
        ) {
            val targetPosition = OnlineTranslationBoundaryPolicy.nextCommitPosition(
                lines = latestNativeSong.lyrics.orEmpty(),
                currentPosition = LyriconDataBridge.currentPosition,
            )
            if (targetPosition != null && targetPosition > LyriconDataBridge.currentPosition) {
                pendingOnlineTranslationCommit = PendingOnlineTranslationCommit(
                    generation = generation,
                    baseSong = baseSong,
                    selection = selection,
                    targetPosition = targetPosition,
                )
                pronunciationDiagnostic(
                    "stage=race_final_deferred, generation=$generation, id=${baseSong.id}, " +
                        "targetPosition=$targetPosition, currentPosition=${LyriconDataBridge.currentPosition}"
                )
                return
            }
        }
        if (publicationStage == OnlineTranslationPublicationStage.RACE_FINAL_COMMIT) {
            pronunciationDiagnostic(
                "stage=race_final_commit, generation=$generation, id=${baseSong.id}, " +
                    "position=${LyriconDataBridge.currentPosition}"
            )
            pendingOnlineTranslationCommit = null
        }
        val nativePublicationEnabled = appleRequest && isNativeOnlineTranslationEnabled()
        pronunciationDiagnostic(
            "stage=publish_attempt, generation=$generation, id=${baseSong.id}, " +
                "enabled=$nativePublicationEnabled, enriched=$hasOnlineEnrichment, " +
                "romanizedLines=$mergedRomanizedLines, bridgePresent=${directBridge != null}, " +
                "publicationStage=$publicationStage, overlayEnabled=$overlayPublicationEnabled"
        )
        if (nativePublicationEnabled && hasOnlineEnrichment) {
            val published = directBridge?.publishOnlineTranslation(
                song = mergedResult.song,
                generation = generation,
            ) == true
            pronunciationDiagnostic(
                "stage=publish_call_result, generation=$generation, id=${baseSong.id}, " +
                    "success=$published, publicationStage=$publicationStage"
            )
        }
        completePendingOnlineSourceSwitchRequests(mergedResult.song)
        if (appleRequest) {
            publishAppleSong(
                mergedResult.song,
                restorePosition = true,
                onlineTranslationMatched = hasOnlineEnrichment,
                publishToSink = overlayPublicationEnabled,
            )
        } else {
            currentPublishedThirdPartySong = mergedResult.song
            publishSong(
                mergedResult.song,
                restorePosition = true,
                onlineTranslationMatched = hasOnlineEnrichment,
            )
        }
    }

    private fun sameOnlineTranslationContent(first: LocalSong, second: LocalSong): Boolean {
        if (!isSameTrack(first, second)) return false
        if (first.lyrics.orEmpty().size != second.lyrics.orEmpty().size) return false
        val firstTranslationSource = first.metadata
            ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
        val secondTranslationSource = second.metadata
            ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
        val firstPronunciationSource = first.metadata
            ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
        val secondPronunciationSource = second.metadata
            ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
        if (
            firstTranslationSource != secondTranslationSource ||
            firstPronunciationSource != secondPronunciationSource
        ) return false
        return first.lyrics.orEmpty().zip(second.lyrics.orEmpty()).all { (left, right) ->
            left.translation == right.translation &&
                left.roma == right.roma &&
                left.metadata == right.metadata
        }
    }

    private fun actualSourceFirst(sourceName: String?, fallbackOrder: List<Source>): List<Source> {
        val actualSource = sourceName
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
            ?: return fallbackOrder
        return listOf(actualSource) + fallbackOrder.filterNot { it == actualSource }
    }

    private fun maybeCommitPendingOnlineTranslation(position: Long) {
        val pending = pendingOnlineTranslationCommit ?: return
        if (position < pending.targetPosition) return
        pendingOnlineTranslationCommit = null
        pronunciationDiagnostic(
            "stage=race_commit_boundary_reached, generation=${pending.generation}, " +
                "id=${pending.baseSong.id}, targetPosition=${pending.targetPosition}, position=$position"
        )
        mainHandler.post {
            applyOnlineTranslationResult(
                generation = pending.generation,
                baseSong = pending.baseSong,
                selection = pending.selection,
                publicationStage = OnlineTranslationPublicationStage.RACE_FINAL_COMMIT,
            )
        }
    }

    private fun requestOriginalMetadata(baseSong: LocalSong, reason: String): Boolean {
        val mediaId = baseSong.id?.takeIf { it.all(Char::isDigit) } ?: return false
        val hasOriginalMetadata = !baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE)
            .isNullOrBlank() || !baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST)
            .isNullOrBlank()
        val originalMetadataResolved = baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_METADATA_RESOLVED)
            .toBoolean()
        if (
            hasOriginalMetadata ||
            originalMetadataResolved ||
            originalMetadataRequestKey == mediaId
        ) return false
        val application = app ?: return false
        originalMetadataRequestKey = mediaId
        application.sendBroadcast(
            Intent(AppleDirectBridgeContract.ACTION_RESOLVE_ORIGINAL_METADATA)
                .setPackage(APPLE_MUSIC_PACKAGE)
                .putExtra(AppleDirectBridgeContract.EXTRA_MEDIA_ID, mediaId)
        )
        HookLogger.i(
            TAG,
            "Apple Music 三方检索未命中，请求多地区原名: " +
                "id=$mediaId, title=${baseSong.name}, reason=$reason"
        )
        return true
    }

    private fun shouldRequestOriginalMetadataForOnlineLookup(song: LocalSong): Boolean {
        if (!shouldPreferAppleOriginalMetadata()) return false
        if (
            song.metadata
                ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_METADATA_RESOLVED)
                .toBoolean()
        ) return false
        return AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
            mediaId = song.id,
            title = song.name,
            artist = song.artist,
            genre = song.metadata?.getString(LyricMetadataKeys.APPLE_CATALOG_GENRE),
        )
    }

    private fun cancelOnlineTranslation(
        clearAttempt: Boolean,
        clearMatched: Boolean,
        reason: String
    ) {
        if (onlineTranslationJob?.isActive == true || onlineMatchedTranslationActive) {
            diagnostic(
                "Apple Music 在线翻译匹配取消: reason=$reason, " +
                    "title=${currentAppleSong?.name}"
            )
        }
        onlineTranslationGeneration += 1
        onlineTranslationJob?.cancel()
        onlineTranslationJob = null
        pendingOnlineTranslationCommit = null
        onlineRaceFirstPublishedGeneration = null
        onlineRaceFirstAcceptedGeneration = null
        if (clearAttempt) onlineTranslationAttemptKey = null
        if (clearMatched) {
            directBridge?.clearOnlineTranslation(currentAppleSong?.id)
            onlineMatchedTranslationActive = false
        }
    }

    private fun startAppleMediaMonitor() {
        lastObservedMediaKey = null
        mainHandler.removeCallbacks(appleMediaMonitor)
        mainHandler.post(appleMediaMonitor)
    }

    private fun stopAppleMediaMonitor() {
        mainHandler.removeCallbacks(appleMediaMonitor)
        lastObservedMediaKey = null
    }

    private fun observeAppleMediaSession(force: Boolean = false) {
        val application = app ?: return
        if (hasNonAppleCentralPlayer()) return
        val media = MediaMetadataHelper.getMediaInfo(application, APPLE_MUSIC_PACKAGE, HookLogger)
        if (media.title.isBlank()) return
        updateAppleMediaPositionReference(media)
        if (!isOnlineTranslationEnabledFor(APPLE_MUSIC_PACKAGE) &&
            !isFillMissingLyricsEnabled()
        ) {
            return
        }

        val mediaSong = LocalSong(
            name = media.title,
            artist = media.artist,
            duration = media.duration.coerceAtLeast(0L),
            lyrics = emptyList()
        )
        val mediaKey = songIdentity(mediaSong)
        if (!force && mediaKey == lastObservedMediaKey) return
        lastObservedMediaKey = mediaKey

        val nativeSong = currentAppleSong
        if (
            AppleSongUpdatePolicy.canStartFallbackFromMediaSession(
                currentSong = nativeSong,
                mediaSessionSong = mediaSong,
                currentHasNativeLyrics = currentAppleHasNativeLyrics
            )
        ) {
            val fallbackPending = fallbackDelayRunnable != null || fallbackJob?.isActive == true
            if (!fallbackPending && !fallbackSongActive) {
                diagnostic(
                    "Apple Music Provider 已确认无歌词，媒体会话补充触发在线兜底: title=${media.title}, " +
                        "artist=${media.artist}, duration=${media.duration}"
                )
                scheduleFallback(nativeSong ?: return, 0L)
            }
            return
        }

        diagnostic(
            "Apple Music 媒体会话只用于观察，等待原生歌词通道确认: " +
                "title=${media.title}, artist=${media.artist}, duration=${media.duration}"
        )
    }

    private fun refreshAppleMediaPositionReference() {
        val application = app ?: return
        val media = MediaMetadataHelper.getMediaInfo(application, APPLE_MUSIC_PACKAGE, HookLogger)
        if (media.title.isBlank()) return
        updateAppleMediaPositionReference(media)
    }

    private fun updateAppleMediaPositionReference(media: MediaMetadataHelper.MediaInfo) {
        val application = app ?: return
        val currentSong = currentAppleSong ?: return
        val previous = appleMediaPositionReference
        val matchesCurrentSong = AppleCentralPositionPolicy.matchesTrack(
            firstTitle = currentSong.name,
            firstArtist = currentSong.artist,
            firstDuration = currentSong.duration,
            secondTitle = media.title,
            secondArtist = media.artist,
            secondDuration = media.duration,
        )
        val continuesBoundMediaIdentity = previous?.songGeneration == appleSongGeneration &&
            AppleCentralPositionPolicy.matchesTrack(
                firstTitle = previous.title,
                firstArtist = previous.artist,
                firstDuration = previous.duration,
                secondTitle = media.title,
                secondArtist = media.artist,
                secondDuration = media.duration,
            )
        if (!matchesCurrentSong && !continuesBoundMediaIdentity) return

        val progress = MediaMetadataHelper.getPlaybackProgress(application, APPLE_MUSIC_PACKAGE)
        if (progress.position < 0L) return
        appleMediaPositionReference = AppleCentralPositionPolicy.MediaReference(
            songGeneration = appleSongGeneration,
            title = media.title,
            artist = media.artist,
            duration = media.duration.takeIf { it > 0L } ?: progress.duration,
            position = progress.position,
            isPlaying = progress.isPlaying,
            playbackSpeed = progress.playbackSpeed,
            observedAtMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun resolveApplePosition(
        adjustedPosition: Long,
        explicitSeek: Boolean,
    ): AppleCentralPositionPolicy.Resolution = AppleCentralPositionPolicy.resolve(
        centralPosition = adjustedPosition,
        currentSongDuration = currentPublishedAppleSong?.duration
            ?.takeIf { it > 0L }
            ?: currentAppleSong?.duration
            ?: 0L,
        currentSongGeneration = appleSongGeneration,
        mediaReference = appleMediaPositionReference,
        directReference = appleDirectPositionReference.takeIf {
            !hasActiveCentralPlayer() || isBuiltInAppleCentralProviderActive()
        },
        providerDelayMs = activeProviderDelayMs,
        nowMs = SystemClock.elapsedRealtime(),
        explicitSeek = explicitSeek,
    )

    private fun startMediaPositionPolling() {
        if (mediaPositionJob?.isActive == true) return
        val application = app ?: return
        lastMediaPlaybackState = null
        mediaPositionJob = mediaPositionScope.launch {
            while (isActive && fallbackSongActive) {
                val progress = MediaMetadataHelper.getPlaybackProgress(
                    application,
                    APPLE_MUSIC_PACKAGE
                )
                if (progress.position >= 0L) {
                    lastAdjustedPosition = progress.position
                    sink?.onPositionChanged(progress.position)
                }
                if (lastMediaPlaybackState != progress.isPlaying) {
                    lastMediaPlaybackState = progress.isPlaying
                    sink?.onPlaybackStateChanged(progress.isPlaying)
                }
                delay(33L)
            }
        }
    }

    private fun stopMediaPositionPolling() {
        mediaPositionJob?.cancel()
        mediaPositionJob = null
        lastMediaPlaybackState = null
    }

    private fun isSaltPreferOnlineEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE,
        RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE
    ) ?: RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE

    private fun configuredOnlineSources(): List<Source> =
        OnlineTranslationSourcePreferences.orderedSources(prefs)

    private fun isOnlineTranslationEnabledFor(packageName: String?): Boolean =
        OnlineTranslationSourcePreferences.isAppEnabled(
            prefs,
            packageName ?: APPLE_MUSIC_PACKAGE,
        ) &&
            configuredOnlineSources().isNotEmpty()

    /**
     * 第三方播放器是否允许走在线匹配：
     * 统一按 App 在线翻译开关判定。通用播放器（无官方 Provider）默认开启，
     * 用户可在“在线翻译源”页面对当前播放 App 关闭；有 Provider 接管时同样遵守该开关。
     */
    private fun isThirdPartyOnlineEnabledFor(packageName: String?): Boolean {
        val pkg = packageName ?: return false
        return isOnlineTranslationEnabledFor(pkg)
    }

    private fun isAppleTranslationEnrichmentEnabled(): Boolean =
        isOnlineTranslationEnabledFor(APPLE_MUSIC_PACKAGE) || isNativeOnlineTranslationEnabled()

    private fun isNativeOnlineTranslationEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION

    private fun isFillMissingLyricsEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS

    private fun isLunaBeatWordLyricsEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS

    private fun isHideMandarinPinyinEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN

    private fun shouldPreferAppleOriginalMetadata(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA

    private fun isSimplifyTraditionalLyricsEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS

    private fun hasTranslation(song: LocalSong?): Boolean = song?.lyrics?.any {
        OnlineTranslationContentPolicy.isMeaningful(it.translation)
    } == true

    /**
     * 无歌词曲目可能只有一行“歌名 - 歌手”样式的占位歌词（Spotify、酷狗概念版等会下发，
     * 也可能由展示层生成），不能据此判定本地已有歌词而跳过整首兜底取词。
     */
    private fun isPlaceholderLyrics(song: LocalSong): Boolean {
        val lyrics = song.lyrics ?: return false
        if (lyrics.size != 1) return false
        val text = lyrics.first().text?.trim().orEmpty()
        if (text.isEmpty()) return false
        val compact: (String) -> String = { it.replace(Regex("\\s+"), "") }
        val compactText = compact(text)
        val compactTitle = compact(song.name.orEmpty())
        val compactArtist = compact(song.artist.orEmpty())
        if (compactTitle.isEmpty()) return false
        if (compactText.equals(compactTitle, ignoreCase = true)) return true
        if (compactArtist.isEmpty()) return false
        return compactText.equals("$compactTitle-$compactArtist", ignoreCase = true) ||
            (
                compactText.contains(compactTitle, ignoreCase = true) &&
                    compactText.length <= compactTitle.length + compactArtist.length + 8
                )
    }

    private fun needsOnlineEnrichment(song: LocalSong?): Boolean {
        song ?: return false
        val completeOnlinePronunciation =
            ApplePronunciationVisibilityPolicy.allowsOnlineSupplementation(
                song = song,
                hideMandarinPinyin = isHideMandarinPinyinEnabled(),
            )
        // 全中文歌词不需要在线翻译：语气词（whoa/oh/ayy 等，含符号连接）不改变判定，
        // 避免「所有行都缺翻译」的中文歌被当成外文歌触发在线抓取。
        val fullyChinese = ChineseLyricsPolicy.isFullyChinese(song)
        return song.lyrics?.any {
            !it.text.isNullOrBlank() &&
                (
                    (!fullyChinese && !OnlineTranslationContentPolicy.isMeaningful(it.translation)) ||
                        (completeOnlinePronunciation && it.roma.isNullOrBlank())
                    )
        } == true
    }

    private fun translationIdentity(song: LocalSong): String =
        AppleOnlineTranslationRequestPolicy.attemptKey(song)

    private fun hasActiveCentralPlayer(): Boolean = activeCentralPlayerPackageName != null

    /**
     * 接收模块 app 侧发布的“存在 MediaSession 的包集合”快照。
     * 来源：远端 prefs 变更、配置广播或冷启动读取（见 [HookEntry]）。
     */
    fun onActiveMediaSessionSnapshotChanged(raw: String?, reason: String) {
        activeMediaSessionGate.update(raw)
        MediaCardDiagnosticLogger.log(
            stage = "media_session",
            event = "snapshot_changed",
            reason = reason,
            details = "raw=${MediaCardDiagnosticLogger.sanitize(raw)},tracked=${activeMediaSessionGate.trackedPackages?.sorted()}",
        )
        diagnostic(
            "stage=active_media_session_snapshot, reason=$reason, " +
                "tracked=${activeMediaSessionGate.trackedPackages?.sorted()}",
        )
        evaluateActiveMediaSessionGate()
    }

    /**
     * 活动播放者被门控阻断（Provider 僵尸发布、宿主已无 MediaSession）时，
     * 主动触发一次 sink 清除，恢复超级岛与自定义 AOD 原生显示。
     */
    private fun evaluateActiveMediaSessionGate() {
        val player = activeCentralPlayerPackageName ?: return
        if (!activeMediaSessionGate.isBlocked(player)) {
            mediaSessionGateStopIssued = false
            return
        }
        if (mediaSessionGateStopIssued) return
        mediaSessionGateStopIssued = true
        HookLogger.i(TAG, "活动播放者已无系统 MediaSession，清除歌词显示: player=$player")
        mainHandler.post { sink?.onStop() }
    }

    private fun isCentralPlayerBlockedByMediaSession(): Boolean {
        val blocked = activeMediaSessionGate.isBlocked(activeCentralPlayerPackageName)
        if (blocked) evaluateActiveMediaSessionGate()
        return blocked
    }

    private fun hasNonAppleCentralPlayer(): Boolean {
        val packageName = activeCentralPlayerPackageName
        return packageName != null && packageName != APPLE_MUSIC_PACKAGE
    }

    private fun isSameTrack(first: LocalSong, second: LocalSong): Boolean {
        val firstId = first.id?.trim().orEmpty()
        val secondId = second.id?.trim().orEmpty()
        if (firstId.isNotEmpty() && secondId.isNotEmpty() && firstId == secondId) return true

        if (normalizeIdentity(first.name) != normalizeIdentity(second.name)) return false
        if (normalizeIdentity(first.artist) != normalizeIdentity(second.artist)) return false
        return first.duration <= 0L || second.duration <= 0L ||
            abs(first.duration - second.duration) <= SAME_TRACK_DURATION_TOLERANCE_MS
    }

    private fun songIdentity(song: LocalSong): String = listOf(
        normalizeIdentity(song.name),
        normalizeIdentity(song.artist),
        song.duration.toString()
    ).joinToString("|")

    private fun normalizeIdentity(value: String?): String = value.orEmpty().trim().lowercase()

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) HookLogger.d(TAG, message)
    }

    private fun diagnostic(message: String) {
        if (BuildConfig.DEBUG) HookLogger.w(TAG, "[debug] $message")
    }

    private fun activeSourceSwitchTraceRequest(songId: String?): OnlineSourceSwitchRequest? {
        val targetId = songId?.takeIf(String::isNotBlank) ?: return null
        val now = SystemClock.elapsedRealtime()
        return listOfNotNull(
            pendingLyricsSourceRequest,
            pendingTranslationSourceRequest,
            pendingPronunciationSourceRequest,
            latestSourceSwitchTraceRequest,
        ).firstOrNull { request ->
            request.songId == targetId &&
                now - request.startedAtMs <= SOURCE_SWITCH_DIAGNOSTIC_WINDOW_MS
        }
    }

    private fun sourceSwitchCoreStage(
        request: OnlineSourceSwitchRequest?,
        stage: String,
        details: String = "",
    ) {
        if (!BuildConfig.DEBUG || request == null) return
        val elapsedMs = SystemClock.elapsedRealtime() - request.startedAtMs
        val context =
            "contentType=${request.contentType},requested=${request.requestedSource}," +
                "requestElapsedMs=$elapsedMs"
        AppleSourceSwitchPerformanceDiagnostics.coreStage(
            requestId = request.requestId,
            songId = request.songId,
            stage = stage,
            details = if (details.isBlank()) context else "$context,$details",
        )
    }

    private fun sourceSwitchCoreStage(
        songId: String?,
        stage: String,
        details: String = "",
    ) {
        sourceSwitchCoreStage(activeSourceSwitchTraceRequest(songId), stage, details)
    }

    private fun logPlayerVersionSnapshot(
        playerPackageName: String?,
        providerPackageName: String?,
        processName: String?,
        source: String,
    ) {
        if (playerPackageName.isNullOrBlank()) return
        val application = app ?: return
        runCatching {
            application.packageManager.getPackageInfo(playerPackageName, 0)
        }.onSuccess { packageInfo ->
            val versionName = packageInfo.versionName ?: "unknown"
            val versionCode = packageInfo.longVersionCode
            val key = "$playerPackageName|$versionName|$versionCode|$providerPackageName|$processName"
            if (!loggedPlayerVersionSnapshots.add(key)) return@onSuccess
            HookLogger.i(
                TAG,
                "[PlayerVersionDiag] stage=active_player_snapshot, result=resolved, " +
                    "source=$source, player=$playerPackageName, versionName=$versionName, " +
                    "versionCode=$versionCode, provider=$providerPackageName, process=$processName",
            )
        }.onFailure { error ->
            val key = "$playerPackageName|unavailable|$providerPackageName|$processName"
            if (!loggedPlayerVersionSnapshots.add(key)) return@onFailure
            HookLogger.w(
                TAG,
                "[PlayerVersionDiag] stage=active_player_snapshot, result=unavailable, " +
                    "source=$source, player=$playerPackageName, provider=$providerPackageName, " +
                    "process=$processName, error=${error.javaClass.simpleName}:${error.message}",
            )
        }
    }

    private fun pronunciationDiagnostic(message: String) {
        if (BuildConfig.DEBUG) Log.i(PRONUNCIATION_DIAGNOSTIC_TAG, message)
    }

    private fun debugError(message: String, error: Throwable) {
        if (BuildConfig.DEBUG) HookLogger.e(TAG, message, error)
    }


    private fun initializeSubscriber(app: Application) {
        diagnostic("stage=subscriber_create_started, appPackage=${app.packageName}")
        val sub = LyriconFactory.createSubscriber(app)
        subscriber = sub

        sub.addConnectionListener(connectionListener)
        val subscribed = sub.subscribeActivePlayer(activePlayerListener)
        diagnostic(
            "stage=subscriber_listener_registered, result=$subscribed, " +
                "subscriberType=${sub.javaClass.name}",
        )
        sub.register()
        diagnostic("stage=subscriber_registration_requested")
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnected(subscriber: LyriconSubscriber) {
            MediaCardDiagnosticLogger.log(
                stage = "subscriber",
                event = "connected",
                details = "subscriber=${MediaCardDiagnosticLogger.identity(subscriber)}",
            )
            HookLogger.i(TAG, "订阅连接已建立")
            diagnostic("stage=subscriber_connected")
            mainHandler.post { onCentralConnected?.invoke() }
        }

        override fun onReconnected(subscriber: LyriconSubscriber) {
            MediaCardDiagnosticLogger.log(
                stage = "subscriber",
                event = "reconnected",
                details = "subscriber=${MediaCardDiagnosticLogger.identity(subscriber)}",
            )
            HookLogger.i(TAG, "订阅连接已恢复")
            diagnostic("stage=subscriber_reconnected")
            mainHandler.post { onCentralConnected?.invoke() }
        }

        override fun onDisconnected(subscriber: LyriconSubscriber) {
            MediaCardDiagnosticLogger.log(
                stage = "subscriber",
                event = "disconnected",
                details = "subscriber=${MediaCardDiagnosticLogger.identity(subscriber)},activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)}",
            )
            centralAppleProviderActive = false
            centralAppleSongAvailable = false
            activeCentralPlayerPackageName = null
            activeProviderPackageName = null
            cancelThirdPartyFallback(reason = "subscriber_disconnected")
            HookLogger.w(TAG, "订阅连接已断开")
            diagnostic("stage=subscriber_disconnected")
        }

        override fun onConnectTimeout(subscriber: LyriconSubscriber) {
            MediaCardDiagnosticLogger.log(
                stage = "subscriber",
                event = "connect_timeout",
                details = "subscriber=${MediaCardDiagnosticLogger.identity(subscriber)}",
            )
            centralAppleProviderActive = false
            centralAppleSongAvailable = false
            activeCentralPlayerPackageName = null
            activeProviderPackageName = null
            cancelThirdPartyFallback(reason = "subscriber_connect_timeout")
            HookLogger.w(TAG, "订阅连接超时")
            diagnostic("stage=subscriber_connect_timeout")
            mainHandler.post {
                onCentralConnectTimeout?.invoke()
                diagnostic("stage=subscriber_retry_requested")
                subscriber.register()
                diagnostic("stage=subscriber_retry_returned")
            }
        }
    }

    private val activePlayerListener = object : ActivePlayerListener {
        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            val playerPackageName = providerInfo?.playerPackageName
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "active_provider_changed_begin",
                details = "provider=${MediaCardDiagnosticLogger.sanitize(providerInfo?.providerPackageName)},player=${MediaCardDiagnosticLogger.sanitize(playerPackageName)},process=${MediaCardDiagnosticLogger.sanitize(providerInfo?.processName)}",
            )
            diagnostic(
                "stage=central_active_provider_callback, " +
                    "provider=${providerInfo?.providerPackageName}, " +
                    "player=$playerPackageName, process=${providerInfo?.processName}",
            )
            logPlayerVersionSnapshot(
                playerPackageName = playerPackageName,
                providerPackageName = providerInfo?.providerPackageName,
                processName = providerInfo?.processName,
                source = "central_provider",
            )
            activeCentralPlayerPackageName = playerPackageName
            centralAppleSongAvailable = false
            evaluateActiveMediaSessionGate()
            if (playerPackageName == null && currentAppleSong != null) {
                centralAppleProviderActive = false
                diagnostic(
                    "忽略 Central 空提供者状态: directTitle=${currentAppleSong?.name}"
                )
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "active_provider_dropped",
                    reason = "empty_player_preserved_direct_song",
                    details = "directTitle=${MediaCardDiagnosticLogger.sanitize(currentAppleSong?.name)}",
                )
                return
            }

            val preserveDirectAppleSong =
                playerPackageName == APPLE_MUSIC_PACKAGE &&
                    providerInfo.providerPackageName == BUILT_IN_PROVIDER_PACKAGE &&
                    currentAppleSong != null
            cancelFallback(
                clearAppleSong = !preserveDirectAppleSong,
                reason = "central_provider_changed",
            )
            cancelThirdPartyFallback(reason = "central_provider_changed")
            cancelOnlineTranslation(
                clearAttempt = true,
                clearMatched = true,
                reason = "central_provider_changed"
            )
            lastAdjustedPosition = 0L
            centralPlaybackPositionWitness.onSinkStopped()
            sink?.onStop()
            centralAppleProviderActive =
                playerPackageName == APPLE_MUSIC_PACKAGE
            if (!centralAppleProviderActive) {
                currentPublishedAppleSong = null
                currentPublishedAppleOnlineTranslationMatched = false
            }
            currentThirdPartySong = null
            currentPublishedThirdPartySong = null
            // 提供器接管/切换时重置兜底状态，并让兜底 Provider 重新评估是否仍需接管（专属 Provider 已接管则退出兜底）。
            activeProviderPackageName = providerInfo?.providerPackageName
            activeProviderDelayMs = resolveDelayPackageName(
                providerInfo?.providerPackageName,
                providerInfo?.playerPackageName,
            )?.let(::readProviderDelay)
                ?: RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
            LyriconDataBridge.updateLyricPackage(playerPackageName)
            // 专属 Provider 接管/退出后，让通用兜底重新评估当前活跃播放器是否仍需兜底。
            universalFallbackProvider?.reconsider()
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "active_provider_changed_complete",
                details = "provider=${MediaCardDiagnosticLogger.sanitize(activeProviderPackageName)},player=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},apple=$centralAppleProviderActive,delayMs=$activeProviderDelayMs,preserveDirectAppleSong=$preserveDirectAppleSong",
            )
            if (preserveDirectAppleSong) {
                currentAppleSong?.let { directSong ->
                    diagnostic(
                        "stage=direct_song_preserved_until_central_snapshot, " +
                            "id=${directSong.id}, title=${directSong.name}"
                    )
                    publishAppleSong(directSong, restorePosition = true)
                }
            }
        }


        override fun onSongChanged(song: LyriconSong?) {
            val localSong = song?.toLocalSong()
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "song_callback_begin",
                details = "incomingId=${MediaCardDiagnosticLogger.sanitize(localSong?.id)},incomingTitle=${MediaCardDiagnosticLogger.sanitize(localSong?.name)},incomingLines=${localSong?.lyrics.orEmpty().size},activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},provider=${MediaCardDiagnosticLogger.sanitize(activeProviderPackageName)}",
            )
            diagnostic(
                "stage=central_song_callback, id=${localSong?.id}, title=${localSong?.name}, " +
                    "lyrics=${localSong?.lyrics.orEmpty().size}, " +
                    "translated=${localSong?.lyrics.orEmpty().count { !it.translation.isNullOrBlank() }}, " +
                    "activePlayer=$activeCentralPlayerPackageName, " +
                    "centralAppleProviderActive=$centralAppleProviderActive",
            )
            if (isCentralPlayerBlockedByMediaSession()) {
                diagnostic(
                    "stage=central_song_dropped, reason=no_active_media_session, " +
                        "title=${localSong?.name}",
                )
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "song_callback_dropped",
                    reason = "no_active_media_session",
                    details = "incomingId=${MediaCardDiagnosticLogger.sanitize(localSong?.id)},incomingTitle=${MediaCardDiagnosticLogger.sanitize(localSong?.name)}",
                )
                return
            }
            if (centralAppleProviderActive) {
                centralAppleSongAvailable = !localSong?.lyrics.isNullOrEmpty()
                cancelThirdPartyFallback(reason = "central_apple_song")
                handleAppleSong(localSong)
            } else {
                if (activeCentralPlayerPackageName == null) {
                    diagnostic(
                        "忽略无活动提供者的 Central 歌曲回调: " +
                            "title=${localSong?.name}, directTitle=${currentAppleSong?.name}"
                    )
                    return
                }
                cancelFallback(clearAppleSong = true, reason = "central_non_apple_song")
                cancelOnlineTranslation(
                    clearAttempt = true,
                    clearMatched = true,
                    reason = "central_non_apple_song"
                )
                handleThirdPartySong(localSong)
            }
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "song_callback_complete",
                details = "incomingId=${MediaCardDiagnosticLogger.sanitize(localSong?.id)},publishedId=${MediaCardDiagnosticLogger.sanitize(LyriconDataBridge.currentSong?.id)},apple=$centralAppleProviderActive,sink=${sink != null}",
            )
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            val blocked = isCentralPlayerBlockedByMediaSession()
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "playback_state_callback",
                details = "isPlaying=$isPlaying,blocked=$blocked,activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},provider=${MediaCardDiagnosticLogger.sanitize(activeProviderPackageName)},sink=${sink != null}",
            )
            if (blocked) {
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "playback_state_dropped",
                    reason = "no_active_media_session",
                    details = "isPlaying=$isPlaying",
                )
                return
            }
            val shouldForward = shouldForwardCentralPlaybackState(
                hasActiveCentralPlayer = hasActiveCentralPlayer(),
                centralAppleProviderActive = centralAppleProviderActive,
                fallbackSongActive = fallbackSongActive,
            )
            if (!shouldForward) {
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "playback_state_dropped",
                    reason = "forward_policy_rejected",
                    details = "isPlaying=$isPlaying,activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},apple=$centralAppleProviderActive,fallback=$fallbackSongActive",
                )
                return
            }
            centralPlaybackPositionWitness.onSinkPlaybackState(isPlaying)
            sink?.onPlaybackStateChanged(isPlaying)
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "playback_state_forwarded",
                details = "isPlaying=$isPlaying",
            )
        }

        override fun onPositionChanged(position: Long) {
            if (!hasActiveCentralPlayer()) {
                logCentralPositionDiagnostic(position, null, "dropped_no_active_player")
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "position_dropped",
                    reason = "no_active_player",
                    details = "rawPosition=$position",
                    positionSample = true,
                )
                return
            }
            val blocked = isCentralPlayerBlockedByMediaSession()
            if (blocked) {
                logCentralPositionDiagnostic(position, null, "dropped_no_active_media_session")
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "position_dropped",
                    reason = "no_active_media_session",
                    details = "rawPosition=$position,activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)}",
                    positionSample = true,
                )
                return
            }
            if (isBuiltInAppleCentralProviderActive() &&
                centralPlaybackPositionWitness.observeActivePosition(SystemClock.elapsedRealtime())
            ) {
                diagnostic(
                    "stage=central_playback_recovered_from_position_witness, " +
                        "position=$position, player=$activeCentralPlayerPackageName, " +
                        "provider=$activeProviderPackageName",
                )
                sink?.onPlaybackStateChanged(true)
            }
            if (centralAppleProviderActive && fallbackSongActive) {
                logCentralPositionDiagnostic(position, null, "dropped_apple_fallback_active")
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "position_dropped",
                    reason = "apple_fallback_active",
                    details = "rawPosition=$position",
                    positionSample = true,
                )
                return
            }
            val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
            if (centralAppleProviderActive) {
                val resolution = resolveApplePosition(adjustedPosition, explicitSeek = false)
                lastAdjustedPosition = AppleCentralPositionPolicy.restorablePosition(
                    previousPosition = lastAdjustedPosition,
                    resolution = resolution,
                )
                logAppleTimingDiagnostic(
                    path = "central",
                    rawPosition = position,
                    adjustedPosition = adjustedPosition,
                    resolution = resolution,
                )
                val resolvedPosition = resolution.position
                if (resolvedPosition == null) {
                    logCentralPositionDiagnostic(position, null, "dropped_apple_resolution")
                    MediaCardDiagnosticLogger.log(
                        stage = "central",
                        event = "position_dropped",
                        reason = "apple_resolution_null",
                        details = "rawPosition=$position,adjustedPosition=$adjustedPosition",
                        positionSample = true,
                    )
                    return
                }
                maybeCommitPendingOnlineTranslation(resolvedPosition)
                sink?.onPositionChanged(resolvedPosition)
                logCentralPositionDiagnostic(position, resolvedPosition, "forwarded_apple")
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "position_forwarded",
                    details = "rawPosition=$position,forwardedPosition=$resolvedPosition,apple=true,sink=${sink != null}",
                    positionSample = true,
                )
                return
            }
            maybeCommitPendingOnlineTranslation(adjustedPosition)
            sink?.onPositionChanged(adjustedPosition)
            logCentralPositionDiagnostic(position, adjustedPosition, "forwarded_non_apple")
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "position_forwarded",
                details = "rawPosition=$position,forwardedPosition=$adjustedPosition,apple=false,sink=${sink != null}",
                positionSample = true,
            )
        }


        override fun onSeekTo(position: Long) {
            val blocked = isCentralPlayerBlockedByMediaSession()
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "seek_callback",
                details = "rawPosition=$position,activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},blocked=$blocked,apple=$centralAppleProviderActive,sink=${sink != null}",
                positionSample = true,
            )
            if (!hasActiveCentralPlayer()) return
            if (blocked) return
            if (centralAppleProviderActive && fallbackSongActive) return
            val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
            if (centralAppleProviderActive) {
                val resolution = resolveApplePosition(adjustedPosition, explicitSeek = true)
                lastAdjustedPosition = AppleCentralPositionPolicy.restorablePosition(
                    previousPosition = lastAdjustedPosition,
                    resolution = resolution,
                )
                logAppleTimingDiagnostic(
                    path = "central_seek",
                    rawPosition = position,
                    adjustedPosition = adjustedPosition,
                    resolution = resolution,
                    force = true,
                )
                val resolvedPosition = resolution.position ?: run {
                    MediaCardDiagnosticLogger.log(
                        stage = "central",
                        event = "seek_dropped",
                        reason = "apple_resolution_null",
                        details = "rawPosition=$position,adjustedPosition=$adjustedPosition",
                        positionSample = true,
                    )
                    return
                }
                maybeCommitPendingOnlineTranslation(resolvedPosition)
                sink?.onSeekTo(resolvedPosition)
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "seek_forwarded",
                    details = "rawPosition=$position,forwardedPosition=$resolvedPosition,apple=true",
                    positionSample = true,
                )
                return
            }
            maybeCommitPendingOnlineTranslation(adjustedPosition)
            sink?.onSeekTo(adjustedPosition)
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "seek_forwarded",
                details = "rawPosition=$position,forwardedPosition=$adjustedPosition,apple=false",
                positionSample = true,
            )
        }

        override fun onReceiveText(text: String?) {
            val controlFrame = OfficialProviderSubscriberControlFrame.inspect(text)
            if (controlFrame.consumed) {
                val providerPackage = activeProviderPackageName
                val playerPackage = activeCentralPlayerPackageName
                val result = if (
                    controlFrame.frame != null &&
                    !providerPackage.isNullOrBlank() &&
                    !playerPackage.isNullOrBlank()
                ) {
                    NextTrackMetadataCache.accept(
                        providerPackageName = providerPackage,
                        playerPackageName = playerPackage,
                        frame = controlFrame.frame,
                    ).name
                } else {
                    "FILTERED_WITHOUT_CACHE"
                }
                if (BuildConfig.DEBUG) {
                    HookLogger.i(
                        TAG,
                        "外置 Central 控制帧已消费: result=$result, " +
                            "provider=$providerPackage, player=$playerPackage",
                    )
                }
                return
            }
            if (!hasActiveCentralPlayer()) return
            if (centralAppleProviderActive && fallbackSongActive) return
            sink?.onPlainText(
                if (centralAppleProviderActive) simplifyAppleTextForDisplay(text) else text
            )
        }

        // 提供器只负责提供歌词内容；翻译和罗马音是否显示由 HyperLyrics 显示端配置决定。
        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
    }

    internal fun onDirectSongChanged(song: LyriconSong?) {
        val localSong = song?.toLocalSong()
        diagnostic(
            "stage=direct_song_callback, id=${localSong?.id}, title=${localSong?.name}, " +
                "lyrics=${localSong?.lyrics.orEmpty().size}, " +
                "activeCentralPlayer=$activeCentralPlayerPackageName",
        )
        currentDirectAppleSongId = localSong?.id
        appleDirectPositionReference = null
        val acceptDirect = AppleDirectSongRecoveryPolicy.shouldAccept(
            activePlayerPackage = activeCentralPlayerPackageName,
            activeProviderPackage = activeProviderPackageName,
            centralSongAvailable = centralAppleSongAvailable,
            appleMusicPackage = APPLE_MUSIC_PACKAGE,
            builtInProviderPackage = BUILT_IN_PROVIDER_PACKAGE,
        )
        if (!acceptDirect) {
            diagnostic(
                "stage=direct_song_callback_dropped, reason=central_song_authoritative, " +
                    "centralSongAvailable=$centralAppleSongAvailable"
            )
            return
        }
        if (localSong != null) {
            logPlayerVersionSnapshot(
                playerPackageName = APPLE_MUSIC_PACKAGE,
                providerPackageName = BUILT_IN_PROVIDER_PACKAGE,
                processName = APPLE_MUSIC_PACKAGE,
                source = "apple_direct",
            )
        }
        val providerPackage = activeProviderPackageName ?: BUILT_IN_PROVIDER_PACKAGE
        activeProviderPackageName = providerPackage
        activeProviderDelayMs = readProviderDelay(providerPackage)
        LyriconDataBridge.updateLyricPackage(APPLE_MUSIC_PACKAGE)
        handleAppleSong(localSong)
    }

    internal fun onDirectPlaybackStateChanged(isPlaying: Boolean) {
        if (!hasActiveCentralPlayer() && !fallbackSongActive) {
            sink?.onPlaybackStateChanged(isPlaying)
        }
    }

    internal fun onDirectPositionChanged(position: Long) {
        if (fallbackSongActive) return
        if (hasActiveCentralPlayer() && !centralAppleProviderActive) return
        if (centralAppleProviderActive && !isBuiltInAppleCentralProviderActive()) return
        if (centralAppleProviderActive && !directSongMatchesCurrentAppleSong()) return
        val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
        appleDirectPositionReference = AppleCentralPositionPolicy.DirectReference(
            songGeneration = appleSongGeneration,
            position = adjustedPosition,
            observedAtMs = SystemClock.elapsedRealtime(),
        )
        val resolution = resolveApplePosition(adjustedPosition, explicitSeek = false)
        lastAdjustedPosition = AppleCentralPositionPolicy.restorablePosition(
            previousPosition = lastAdjustedPosition,
            resolution = resolution,
        )
        logAppleTimingDiagnostic(
            if (centralAppleProviderActive) "direct_primary" else "direct",
            position,
            adjustedPosition,
            resolution = resolution,
        )
        resolution.position?.let {
            maybeCommitPendingOnlineTranslation(it)
            sink?.onPositionChanged(it)
        }
    }

    internal fun onDirectSeekTo(position: Long) {
        if (fallbackSongActive) return
        if (hasActiveCentralPlayer() && !centralAppleProviderActive) return
        if (centralAppleProviderActive && !isBuiltInAppleCentralProviderActive()) return
        if (centralAppleProviderActive && !directSongMatchesCurrentAppleSong()) return
        val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
        appleDirectPositionReference = AppleCentralPositionPolicy.DirectReference(
            songGeneration = appleSongGeneration,
            position = adjustedPosition,
            observedAtMs = SystemClock.elapsedRealtime(),
        )
        val resolution = resolveApplePosition(adjustedPosition, explicitSeek = true)
        lastAdjustedPosition = AppleCentralPositionPolicy.restorablePosition(
            previousPosition = lastAdjustedPosition,
            resolution = resolution,
        )
        logAppleTimingDiagnostic(
            "direct_seek",
            position,
            adjustedPosition,
            resolution = resolution,
            force = true,
        )
        resolution.position?.let {
            maybeCommitPendingOnlineTranslation(it)
            sink?.onSeekTo(it)
        }
    }

    private fun directSongMatchesCurrentAppleSong(): Boolean {
        val directSongId = currentDirectAppleSongId ?: return false
        val currentSongId = currentAppleSong?.id ?: return false
        return directSongId == currentSongId
    }

    private fun isBuiltInAppleCentralProviderActive(): Boolean =
        centralAppleProviderActive && activeProviderPackageName == BUILT_IN_PROVIDER_PACKAGE

    private fun logAppleTimingDiagnostic(
        path: String,
        rawPosition: Long,
        adjustedPosition: Long,
        resolution: AppleCentralPositionPolicy.Resolution? = null,
        force: Boolean = false,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val state = listOf(
            path,
            currentAppleSong?.id,
            currentPublishedAppleSong?.id,
            activeCentralPlayerPackageName,
            activeProviderPackageName,
            activeProviderDelayMs,
            fallbackSongActive,
            resolution?.reason,
        ).joinToString("|")
        if (
            !force &&
            state == lastTimingDiagnosticState &&
            now - lastTimingDiagnosticAtMs < TIMING_DIAGNOSTIC_INTERVAL_MS
        ) return
        HookLogger.i(
            TAG,
            "[debug] Timing receive: path=$path, rawPosition=$rawPosition, " +
                "adjustedPosition=$adjustedPosition, " +
                "resolvedPosition=${resolution?.position ?: adjustedPosition}, " +
                "mediaPosition=${resolution?.mediaPosition}, " +
                "directPosition=${resolution?.directPosition}, decision=${resolution?.reason}, " +
                "positionDelta=${(adjustedPosition - lastTimingDiagnosticPosition).takeIf {
                    lastTimingDiagnosticPosition >= 0L
                }}, delayMs=$activeProviderDelayMs, currentSongId=${currentAppleSong?.id}, " +
                "publishedSongId=${currentPublishedAppleSong?.id}, " +
                "centralPlayer=$activeCentralPlayerPackageName, " +
                "provider=$activeProviderPackageName, fallback=$fallbackSongActive"
        )
        lastTimingDiagnosticAtMs = now
        lastTimingDiagnosticPosition = adjustedPosition
        lastTimingDiagnosticState = state
    }

    private fun logCentralPositionDiagnostic(
        rawPosition: Long,
        forwardedPosition: Long?,
        decision: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCentralPositionDiagnosticAtMs < TIMING_DIAGNOSTIC_INTERVAL_MS) return
        lastCentralPositionDiagnosticAtMs = now
        HookLogger.i(
            TAG,
            "[debug] [LyricPositionDiag] stage=subscriber_callback, decision=$decision, " +
                "rawPosition=$rawPosition, forwardedPosition=$forwardedPosition, " +
                "centralPlayer=$activeCentralPlayerPackageName, " +
                "provider=$activeProviderPackageName, delayMs=$activeProviderDelayMs, " +
                "appleCentral=$centralAppleProviderActive, fallback=$fallbackSongActive, " +
                "sinkAvailable=${sink != null}"
        )
    }

    internal fun onDirectText(text: String?) {
        if (!hasActiveCentralPlayer() && !fallbackSongActive) {
            sink?.onPlainText(simplifyAppleTextForDisplay(text))
        }
    }

    internal fun onDirectOnlineLyricContentSourceRequested(
        requestId: Long,
        songId: String?,
        contentType: String?,
        sourceName: String?,
    ) {
        val nativeSong = currentAppleSong
        if (
            contentType == "lyrics" &&
            sourceName == APPLE_NATIVE_LYRICS_SOURCE &&
            restoreAppleNativeLyricsSource(requestId, songId)
        ) {
            return
        }
        val requestedSource = runCatching { Source.valueOf(sourceName.orEmpty()) }
            .getOrNull()
        AppleSourceSwitchPerformanceDiagnostics.coreStage(
            requestId = requestId,
            songId = songId,
            stage = "request_received",
            details = "contentType=${contentType ?: "none"},source=${sourceName ?: "none"}," +
                "currentSongId=${nativeSong?.id ?: "none"},fallbackGeneration=$fallbackGeneration," +
                "onlineTranslationGeneration=$onlineTranslationGeneration",
        )
        if (
            nativeSong == null ||
            songId.isNullOrBlank() ||
            songId != nativeSong.id ||
            requestedSource == null ||
            contentType !in setOf("translation", "pronunciation", "lyrics") ||
            (requestedSource == Source.LB && contentType != "lyrics") ||
            (contentType == "lyrics" && requestedSource == Source.LB &&
                !isLunaBeatWordLyricsEnabled()) ||
            (contentType == "lyrics" && requestedSource != Source.LB &&
                !isFillMissingLyricsEnabled())
        ) {
            AppleSourceSwitchPerformanceDiagnostics.coreStage(
                requestId = requestId,
                songId = songId,
                stage = "request_rejected",
                details = "contentType=${contentType ?: "none"},source=${sourceName ?: "none"}," +
                    "currentSongId=${nativeSong?.id ?: "none"},requestedSource=$requestedSource," +
                    "fillMissingLyrics=${isFillMissingLyricsEnabled()}",
            )
            diagnostic(
                "Apple Music 在线翻译来源切换拒绝: requestId=$requestId, " +
                    "songId=$songId, currentSongId=${nativeSong?.id}, " +
                    "contentType=$contentType, source=$sourceName"
            )
            directBridge?.publishOnlineTranslationSourceSwitchResult(
                requestId = requestId,
                songId = songId,
                contentType = contentType,
                requestedSource = sourceName,
                actualSource = null,
                successful = false,
            )
            return
        }
        val request = OnlineSourceSwitchRequest(
            requestId = requestId,
            songId = requireNotNull(songId),
            contentType = requireNotNull(contentType),
            requestedSource = requestedSource,
            startedAtMs = SystemClock.elapsedRealtime(),
        )
        latestSourceSwitchTraceRequest = request
        sourceSwitchCoreStage(
            request = request,
            stage = "request_accepted",
            details = "fallbackGeneration=$fallbackGeneration," +
                "onlineTranslationGeneration=$onlineTranslationGeneration",
        )
        when (contentType) {
            "translation" -> {
                temporaryTranslationSource = requestedSource
                pendingTranslationSourceRequest = request
            }
            "pronunciation" -> {
                temporaryPronunciationSource = requestedSource
                pendingPronunciationSourceRequest = request
            }
            "lyrics" -> {
                pendingLyricsSourceRequest = request
                diagnostic(
                    "Apple Music 歌词来源切换接受: requestId=$requestId, " +
                        "songId=$songId, source=$requestedSource"
                )
                val previousFallbackGeneration = fallbackGeneration
                val previousFallbackJobActive = fallbackJob?.isActive == true
                val previousFallbackDelayPending = fallbackDelayRunnable != null
                cancelFallback(clearAppleSong = false, reason = "temporary_lyrics_source_switched")
                sourceSwitchCoreStage(
                    request = request,
                    stage = "previous_fallback_cancelled",
                    details = "generation=$previousFallbackGeneration->$fallbackGeneration," +
                        "jobActive=$previousFallbackJobActive," +
                        "delayPending=$previousFallbackDelayPending",
                )
                // 歌词正文即将换成新来源，旧翻译任务必须作废并重新按新时间轴匹配；
                // 但已显示的翻译继续保留到新补充载荷到达，避免 Apple Music、超级岛
                // 和 AOD 在切换窗口先被主动清空。
                cancelOnlineTranslation(
                    clearAttempt = true,
                    clearMatched = false,
                    reason = "temporary_lyrics_source_switched",
                )
                sourceSwitchCoreStage(
                    request = request,
                    stage = "previous_translation_cancelled",
                    details = "onlineTranslationGeneration=$onlineTranslationGeneration",
                )
                scheduleFallback(
                    baseSong = nativeSong,
                    delayMs = 0L,
                    preferredSourceOverride = requestedSource,
                    strictSource = true,
                )
                sourceSwitchCoreStage(
                    request = request,
                    stage = "fallback_schedule_returned",
                    details = "fallbackGeneration=$fallbackGeneration",
                )
                return
            }
        }
        diagnostic(
            "Apple Music 在线翻译来源切换接受: requestId=$requestId, " +
                "songId=$songId, contentType=$contentType, source=$requestedSource"
        )
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = false,
            reason = "temporary_" + contentType + "_source_switched",
        )
        sourceSwitchCoreStage(
            request = request,
            stage = "previous_translation_cancelled",
            details = "onlineTranslationGeneration=$onlineTranslationGeneration",
        )
        if (!scheduleOnlineTranslation(nativeSong)) {
            failPendingOnlineSourceSwitchRequest(request)
        }
    }

    private fun restoreAppleNativeLyricsSource(requestId: Long, songId: String?): Boolean {
        val nativeSong = currentAppleNativeSong?.takeIf { candidate ->
            !songId.isNullOrBlank() && candidate.id == songId && hasAppleNativeLyrics(candidate)
        } ?: run {
            directBridge?.publishOnlineTranslationSourceSwitchResult(
                requestId = requestId,
                songId = songId,
                contentType = "lyrics",
                requestedSource = APPLE_NATIVE_LYRICS_SOURCE,
                actualSource = null,
                successful = false,
            )
            return true
        }
        cancelFallback(clearAppleSong = false, reason = "temporary_apple_lyrics_selected")
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = false,
            reason = "temporary_apple_lyrics_selected",
        )
        confirmedLyricsSourceSelection = ConfirmedLyricsSourceSelection(
            songId = requireNotNull(nativeSong.id),
            source = APPLE_NATIVE_LYRICS_SOURCE,
        )
        currentAppleSong = nativeSong
        currentAppleHasNativeLyrics = true
        fallbackSongActive = false
        stopMediaPositionPolling()
        publishAppleSong(nativeSong, restorePosition = true)
        if (needsOnlineEnrichment(nativeSong) && isAppleTranslationEnrichmentEnabled()) {
            scheduleOnlineTranslation(nativeSong)
        }
        directBridge?.publishOnlineTranslationSourceSwitchResult(
            requestId = requestId,
            songId = songId,
            contentType = "lyrics",
            requestedSource = APPLE_NATIVE_LYRICS_SOURCE,
            actualSource = APPLE_NATIVE_LYRICS_SOURCE,
            successful = true,
        )
        return true
    }

    private fun completePendingLyricsSourceRequest(
        song: LocalSong?,
        requestedSource: Source?,
    ) {
        val request = pendingLyricsSourceRequest ?: return
        if (requestedSource != null && request.requestedSource != requestedSource) return
        val actualSource = song
            ?.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
        sourceSwitchCoreStage(
            request = request,
            stage = "lyrics_result_resolved",
            details = "requestedArgument=${requestedSource ?: "none"}," +
                "actual=${actualSource ?: "none"},lines=${song?.lyrics.orEmpty().size}",
        )
        pendingLyricsSourceRequest = null
        if (actualSource == request.requestedSource) {
            confirmedLyricsSourceSelection = ConfirmedLyricsSourceSelection(
                songId = request.songId,
                source = actualSource.name,
            )
        }
        publishOnlineSourceSwitchResult(request, actualSource)
    }

    private fun completePendingOnlineSourceSwitchRequests(song: LocalSong?) {
        val translationSource = song
            ?.metadata
            ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
        val pronunciationSource = song
            ?.metadata
            ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
        listOfNotNull(
            pendingTranslationSourceRequest?.let { it to translationSource },
            pendingPronunciationSourceRequest?.let { it to pronunciationSource },
        ).forEach { (request, actualSource) ->
            sourceSwitchCoreStage(
                request = request,
                stage = "online_result_resolved",
                details = "actual=${actualSource ?: "none"},lines=${song?.lyrics.orEmpty().size}",
            )
            publishOnlineSourceSwitchResult(request, actualSource)
        }
        pendingTranslationSourceRequest = null
        pendingPronunciationSourceRequest = null
    }

    private fun failPendingOnlineSourceSwitchRequest(request: OnlineSourceSwitchRequest) {
        sourceSwitchCoreStage(
            request = request,
            stage = "source_switch_failed_before_publish",
        )
        when (request.contentType) {
            "translation" -> {
                if (pendingTranslationSourceRequest?.requestId == request.requestId) {
                    pendingTranslationSourceRequest = null
                }
            }
            "pronunciation" -> {
                if (pendingPronunciationSourceRequest?.requestId == request.requestId) {
                    pendingPronunciationSourceRequest = null
                }
            }
        }
        publishOnlineSourceSwitchResult(request, actualSource = null)
    }

    private fun publishOnlineSourceSwitchResult(
        request: OnlineSourceSwitchRequest,
        actualSource: Source?,
    ) {
        val successful = actualSource == request.requestedSource
        sourceSwitchCoreStage(
            request = request,
            stage = "result_binder_publish_started",
            details = "actual=${actualSource ?: "none"},successful=$successful," +
                "bridgePresent=${directBridge != null}",
        )
        diagnostic(
            "Apple Music 在线翻译来源切换完成: requestId=${request.requestId}, " +
                "songId=${request.songId}, contentType=${request.contentType}, " +
                "requested=${request.requestedSource}, actual=${actualSource ?: "none"}, " +
                "successful=$successful"
        )
        val publishStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val published = directBridge?.publishOnlineTranslationSourceSwitchResult(
            requestId = request.requestId,
            songId = request.songId,
            contentType = request.contentType,
            requestedSource = request.requestedSource.name,
            actualSource = actualSource?.name,
            successful = successful,
        ) == true
        sourceSwitchCoreStage(
            request = request,
            stage = "result_binder_publish_finished",
            details = "actual=${actualSource ?: "none"},successful=$successful," +
                "published=$published,elapsedMs=" +
                ((SystemClock.elapsedRealtimeNanos() - publishStartedAtNanos) / 1_000_000.0),
        )
    }
}

private data class OnlineSourceSwitchRequest(
    val requestId: Long,
    val songId: String,
    val contentType: String,
    val requestedSource: Source,
    val startedAtMs: Long,
)

private data class ConfirmedLyricsSourceSelection(
    val songId: String,
    val source: String,
)
