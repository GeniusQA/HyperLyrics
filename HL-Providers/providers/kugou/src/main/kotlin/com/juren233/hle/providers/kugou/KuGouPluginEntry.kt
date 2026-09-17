/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import android.app.Application
import android.media.MediaMetadata
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.genius.hyperlyrics.provider.OfficialProviderConstructorTarget
import com.genius.hyperlyrics.provider.OfficialProviderControlProtocol
import com.genius.hyperlyrics.provider.OfficialProviderDexMethodQuery
import com.genius.hyperlyrics.provider.OfficialProviderDexMethodsCallback
import com.genius.hyperlyrics.provider.OfficialProviderDexTypeReference
import com.genius.hyperlyrics.provider.OfficialProviderDexTypeSource
import com.genius.hyperlyrics.provider.OfficialProviderHost
import com.genius.hyperlyrics.provider.OfficialProviderMetadataCallback
import com.genius.hyperlyrics.provider.OfficialProviderMethodCallback
import com.genius.hyperlyrics.provider.OfficialProviderMethodTarget
import com.genius.hyperlyrics.provider.OfficialProviderPlaybackStateCallback
import com.genius.hyperlyrics.provider.OfficialProviderPlugin
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object KuGouPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/KuGou"
    private const val FULL_PACKAGE = "com.kugou.android"
    private const val LITE_PACKAGE = "com.kugou.android.lite"
    private const val FULL_SUPPORT_PROCESS = "$FULL_PACKAGE.support"
    private const val LITE_SUPPORT_PROCESS = "$LITE_PACKAGE.support"
    private const val PROVIDER_PACKAGE = "com.genius.hyperlyrics.provider.kugou"
    private const val NEXT_TRACK_CAPTURE_INTERVAL_MS = 1_000L
    private const val NEXT_TRACK_HEARTBEAT_MS = 10_000L

    // 酷狗 LyricManager 加载本地歌词文件的方法没有稳定混淆名，用方法内部
    // 的错误文案作 DexKit 锚点（与上游 Lyricon Provider 的酷狗实现同锚点）。
    private const val LYRIC_MANAGER_CLASS = "com.kugou.framework.lyric.LyricManager"
    private const val LYRIC_MANAGER_FILE_ANCHOR = "file is not krc or lyc or txt file"
    /** 框架层文件读取入口：旧版（歌词类完全混淆）用它观察歌词文件加载。 */
    private const val FILE_INPUT_STREAM_CLASS = "java.io.FileInputStream"
    private const val UNKNOWN_VERSION_CODE = -1L
    private const val LYRIC_FILE_HOOK_REGISTRATION_TIMEOUT_MS = 30_000L
    private const val MAX_LYRIC_FILE_BYTES = 4L * 1024L * 1024L

    // 单曲最多尝试的搜索候选数：主候选 + 顺位兜底上限，请求封顶。
    private const val MAX_SEARCH_CANDIDATES = 3

    private val installed = AtomicBoolean(false)

    @Volatile
    private var runtime: KuGouRuntime? = null

    override fun install(host: OfficialProviderHost) {
        require(host.packageName == FULL_PACKAGE || host.packageName == LITE_PACKAGE) {
            "Unsupported KuGou package: ${host.packageName}"
        }
        val supportProcess = when (host.packageName) {
            FULL_PACKAGE -> FULL_SUPPORT_PROCESS
            LITE_PACKAGE -> LITE_SUPPORT_PROCESS
            else -> error("unreachable")
        }
        if (host.processName != supportProcess) return

        host.hookApplication { application ->
            if (Application.getProcessName() != supportProcess) return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication

            val provider = runCatching {
                LyriconFactory.createProvider(
                    context = application,
                    providerPackageName = PROVIDER_PACKAGE,
                    playerPackageName = host.packageName,
                ).also {
                    it.player.setDisplayTranslation(true)
                    it.register()
                }
            }.onFailure { error ->
                installed.set(false)
                Log.e(TAG, "酷狗音乐 Provider 注册失败", error)
            }.getOrNull() ?: return@hookApplication

            val currentRuntime = KuGouRuntime(application, provider, host).also { runtime = it }
            currentRuntime.start()
            val versionCode = resolvePackageVersionCode(application, host.packageName)
            if (KuGouVersionProfile.supportsModernDexProfiles(host.packageName, versionCode)) {
                host.resolveDexMethods(
                    application = application,
                    queries = nextTrackQueriesFor(host.packageName),
                    callback = OfficialProviderDexMethodsCallback { targets ->
                        currentRuntime.installNextTrackResolver(targets)
                    },
                )
                currentRuntime.scheduleLyricFileHookRegistration()
            } else {
                // 旧版/未标定版本：歌词类与播放队列类都不存在同名类型（概念版 2.5.5 的
                // 歌词类为 com.kugou.framework.lyric.a/b/.../l，且没有带文件路径入参的
                // 加载方法），语义锚查询只会稳定 count=0 并连带触发自修复。这里不下发
                // DexKit 查询，改用框架层文件读取兜底，并把版本不兼容显式上报。
                currentRuntime.installLegacyLyricFileWatch()
                reportUnsupportedVersionDiagnostics(host, versionCode)
            }
            Log.i(
                TAG,
                "酷狗音乐 Provider 已注册: package=${host.packageName} " +
                    "process=${host.processName} lyricSource=v2-api+local-file",
            )
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                runtime?.provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                runtime?.onMetadata(metadata)
            },
        )
    }

    internal fun nextTrackQueriesFor(packageName: String): List<OfficialProviderDexMethodQuery> {
        val full = packageName == FULL_PACKAGE
        require(full || packageName == LITE_PACKAGE) { "Unsupported KuGou package: $packageName" }
        val managerClass = "com.kugou.framework.service.KGPlayerManager"
        val queueManagerClass = "com.kugou.common.player.manager.QueuePlayerManager"
        val mediaInterface = "com.kugou.common.player.manager.IMedia"
        val managerType = OfficialProviderDexTypeReference(
            queryCacheKey = if (full) "kugou-full-player-singleton-v3" else
                "kugou-lite-player-singleton-v3",
            source = OfficialProviderDexTypeSource.RETURN_TYPE,
        )
        val nextMediaQuery = if (full) {
            // KuGou full 20.7.5 DEX: QueuePlayerManager.k() -> IMedia reads the next
            // item through PlayQueue.w():int and PlayQueue.v(int):Object. Unlike Lite,
            // the full APK defines no method named getNextMedia in any classes*.dex,
            // so the Lite caller anchor must never be reused here.
            OfficialProviderDexMethodQuery(
                cacheKey = "kugou-full-next-media-v2",
                preferredTarget = OfficialProviderMethodTarget(
                    className = queueManagerClass,
                    methodName = "k",
                    returnTypeName = mediaInterface,
                    isStatic = false,
                ),
                declaringClassName = queueManagerClass,
                requiredInvokedMethodNames = listOf("w", "v"),
                parameterTypeNames = emptyList(),
                returnTypeName = mediaInterface,
                isStatic = false,
            )
        } else {
            // Original KuGou Lite 5.2.4 DEX proves k() reads the current item through
            // PlayQueue.n(). The real next implementation is currently P0(), but its
            // stable bridge getNextMedia() calls it. Resolve by that caller relationship
            // so a future obfuscation rename cannot silently turn the current item into next.
            OfficialProviderDexMethodQuery(
                cacheKey = "kugou-lite-next-media-v2",
                declaringClassName = queueManagerClass,
                requiredCallerMethodNames = listOf("getNextMedia"),
                parameterTypeNames = emptyList(),
                returnTypeName = mediaInterface,
                isStatic = false,
            )
        }
        return listOf(
            OfficialProviderDexMethodQuery(
                cacheKey = managerType.queryCacheKey,
                preferredTarget = OfficialProviderMethodTarget(
                    className = managerClass,
                    methodName = if (full) "K4" else "c4",
                    returnTypeName = managerClass,
                    isStatic = true,
                ),
                declaringClassName = managerClass,
                parameterTypeNames = emptyList(),
                returnTypeName = managerClass,
                isStatic = true,
            ),
            nextMediaQuery,
        )
    }

    internal fun lyricFileQuery(): OfficialProviderDexMethodQuery = OfficialProviderDexMethodQuery(
        cacheKey = "kugou-lyric-file-load-v1",
        declaringClassName = LYRIC_MANAGER_CLASS,
        requiredStrings = listOf(LYRIC_MANAGER_FILE_ANCHOR),
        parameterTypeNames = listOf("java.lang.String", "boolean"),
        isStatic = false,
    )

    private fun resolvePackageVersionCode(application: Application, packageName: String): Long =
        runCatching {
            val info = application.packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(UNKNOWN_VERSION_CODE)

    /**
     * 显式上报“该 App 版本不使用语义锚”。
     *
     * 这些版本上对应类型根本不存在，DexKit 查询必然 count=0；提前把原因写进
     * Provider 校验通道，用户/日志侧就能直接看到版本原因，而不是一连串无意义的
     * “查询结果必须唯一: count=0”与自修复记录。
     */
    private fun reportUnsupportedVersionDiagnostics(
        host: OfficialProviderHost,
        versionCode: Long,
    ) {
        val detail = "unsupported_app_version:$versionCode"
        nextTrackQueriesFor(host.packageName).forEach { query ->
            host.reportDexMethodValidation(query.cacheKey, false, detail)
        }
        host.reportDexMethodValidation(
            "kugou-lyric-file-load-v1",
            false,
            "$detail,fallback=framework_file_watch",
        )
    }

    private class KuGouRuntime(
        private val application: Application,
        val provider: LyriconProvider,
        private val host: OfficialProviderHost,
    ) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-KuGou-Lyrics").apply { isDaemon = true }
        }
        private val generation = AtomicLong(0L)
        private val cacheDir = File(application.filesDir, "hle-provider/kugou")
        private val clientMid = KuGouApiProtocol.clientMid(
            buildString {
                append(application.packageName)
                append('\u0000')
                append(
                    Settings.Secure.getString(
                        application.contentResolver,
                        Settings.Secure.ANDROID_ID,
                    ).orEmpty(),
                )
            },
        )
        private val mainHandler = Handler(Looper.getMainLooper())
        private val periodicNextTrackCapture = object : Runnable {
            override fun run() {
                captureNextTrack()
                if (nextTrackResolver != null) {
                    mainHandler.postDelayed(this, NEXT_TRACK_CAPTURE_INTERVAL_MS)
                }
            }
        }
        private val nextTrackValidation = KuGouNextTrackValidationTracker()

        @Volatile
        private var track = KuGouTrackMetadata(null, null, null, null, 0L)

        @Volatile
        private var lastSong: Song? = null

        @Volatile
        private var nextTrackResolver: KuGouNextTrackResolver? = null

        @Volatile
        private var nextTrackValidationKeys: List<String> = emptyList()

        private var pendingLyricsTask: Future<*>? = null

        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L

        private val lyricHookRegistered = AtomicBoolean(false)
        private val firstLyricFileHit = AtomicBoolean(false)
        private val lyricHookTimeoutRegistration = Runnable { registerLyricFileHook() }
        private val legacyWatchRegistered = AtomicBoolean(false)
        private val legacyHitGuard = KuGouLyricFileHitGuard()

        // 本地歌词文件源（酷狗自读的精确匹配）最近一次发布结果；本地文件源
        // 优先于 v2 搜索源，命中后同曲不再发起搜索、也不再被搜索结果覆盖。
        @Volatile
        private var fileSourceIdentity: String? = null

        @Volatile
        private var fileSourceSong: Song? = null

        fun start() {
            cacheDir.mkdirs()
            publish(placeholder(track))
        }

        fun scheduleLyricFileHookRegistration() {
            mainHandler.postDelayed(
                lyricHookTimeoutRegistration,
                LYRIC_FILE_HOOK_REGISTRATION_TIMEOUT_MS,
            )
        }

        fun onMetadata(value: MediaMetadata?) {
            val next = KuGouTrackMetadata(
                mediaId = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = value?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = value?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            )
            val previous = track
            if (next == previous) return
            track = next
            if (!KuGouTrackUpdatePolicy.shouldReloadLyrics(previous, next)) {
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "酷狗同曲元数据已更新，保留现有歌词: id=${next.identity}, " +
                            "album=${next.album}",
                    )
                }
                mainHandler.post(::captureNextTrack)
                return
            }
            val requestGeneration = generation.incrementAndGet()
            pendingLyricsTask?.cancel(true)
            pendingLyricsTask = null
            val localFileSong = fileSourceSong
                ?.takeIf { KuGouLyricFilePolicy.ownsTrack(fileSourceIdentity, next.identity) }
            publish(localFileSong ?: placeholder(next))
            if (localFileSong == null && next.isSearchable) {
                pendingLyricsTask = executor.submit {
                    loadLyrics(requestGeneration, next)
                }
            }
            mainHandler.post(::captureNextTrack)
        }

        private fun loadLyrics(
            requestGeneration: Long,
            requestTrack: KuGouTrackMetadata,
        ) {
            val candidates = runCatching { KuGouApiClient.searchCandidates(requestTrack, clientMid) }
                .onFailure { error ->
                    if (!Thread.currentThread().isInterrupted) {
                        Log.w(
                            TAG,
                            "酷狗歌词搜索失败: title=${requestTrack.title}",
                            error,
                        )
                    }
                }
                .getOrNull()
            if (candidates.isNullOrEmpty()) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.w(
                        TAG,
                        "酷狗歌词未匹配: title=${requestTrack.title}, " +
                            "artist=${requestTrack.artist}, " +
                            "mediaId=${requestTrack.mediaId != null}",
                    )
                }
                return
            }
            if (!isCurrent(requestGeneration, requestTrack)) return
            if (KuGouLyricFilePolicy.ownsTrack(fileSourceIdentity, requestTrack.identity)) return

            // 主候选优先发布；只有主候选内容不带翻译段时，才在同样通过严格
            // 评分门槛的顺位候选里找带翻译的版本，打分与匹配标准保持不变。
            var primaryCandidate: KuGouSearchCandidate? = null
            var primaryParsed: List<ParsedLine>? = null
            for (candidate in candidates.take(MAX_SEARCH_CANDIDATES)) {
                if (!isCurrent(requestGeneration, requestTrack)) return
                val parsed = resolveLyrics(requestGeneration, candidate, requestTrack) ?: return
                if (parsed.isEmpty()) continue
                if (hasTranslation(parsed)) {
                    publishSearchLyrics(candidate, parsed, requestTrack, requestGeneration)
                    return
                }
                if (primaryParsed == null) {
                    primaryCandidate = candidate
                    primaryParsed = parsed
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            TAG,
                            "酷狗主候选无翻译段，尝试顺位候选: id=${candidate.downloadId}, " +
                                "lines=${parsed.size}",
                        )
                    }
                }
            }
            val fallbackCandidate = primaryCandidate
            val fallbackParsed = primaryParsed
            if (fallbackCandidate == null || fallbackParsed == null) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.w(
                        TAG,
                        "酷狗歌词无可解析时间轴: ids=" +
                            candidates.take(MAX_SEARCH_CANDIDATES)
                                .joinToString { it.downloadId },
                    )
                }
                return
            }
            publishSearchLyrics(fallbackCandidate, fallbackParsed, requestTrack, requestGeneration)
        }

        private fun hasTranslation(parsed: List<ParsedLine>): Boolean =
            // 个别元数据行（词/曲署名）缺翻译是正常形态；全都没有才视为无翻译
            parsed.any { !it.translation.isNullOrBlank() }

        /** 返回 null 表示任务已取消或被新曲取代；空列表表示该候选不可解析。 */
        private fun resolveLyrics(
            requestGeneration: Long,
            candidate: KuGouSearchCandidate,
            requestTrack: KuGouTrackMetadata,
        ): List<ParsedLine>? {
            loadCached(candidate.downloadId)?.let { cached ->
                val parsed = decodeLyrics(cached, requestTrack.durationMs)
                if (parsed.isNotEmpty()) return parsed
            }
            val raw = runCatching { KuGouApiClient.download(candidate, clientMid) }
                .onFailure { error ->
                    if (!Thread.currentThread().isInterrupted) {
                        Log.w(TAG, "酷狗歌词下载失败: id=${candidate.downloadId}", error)
                    }
                }
                .getOrNull() ?: return emptyList()
            if (!isCurrent(requestGeneration, requestTrack)) return null
            val parsed = decodeLyrics(raw, requestTrack.durationMs)
            if (parsed.isEmpty()) return emptyList()
            writeCache(candidate.downloadId, raw)
            return parsed
        }

        private fun publishSearchLyrics(
            candidate: KuGouSearchCandidate,
            parsed: List<ParsedLine>,
            requestTrack: KuGouTrackMetadata,
            requestGeneration: Long,
        ) {
            if (!isCurrent(requestGeneration, requestTrack)) return
            if (KuGouLyricFilePolicy.ownsTrack(fileSourceIdentity, requestTrack.identity)) return
            publish(toSong(requestTrack, parsed))
            if (BuildConfig.DEBUG) {
                val translationCount = parsed.count { !it.translation.isNullOrBlank() }
                Log.i(
                    TAG,
                    "酷狗 v2 歌词已发布: id=${candidate.downloadId}, " +
                        "contentType=${candidate.contentType}, lines=${parsed.size}, " +
                        "words=${parsed.sumOf { it.words.size }}, translations=$translationCount",
                )
            }
        }

        fun installNextTrackResolver(targets: List<OfficialProviderMethodTarget>) {
            mainHandler.post {
                onDexBatchResolved()
                mainHandler.removeCallbacks(periodicNextTrackCapture)
                nextTrackResolver = null
                nextTrackValidation.reset()
                nextTrackValidationKeys = nextTrackQueriesFor(host.packageName)
                    .map { it.cacheKey }
                val resolver = runCatching {
                    KuGouNextTrackResolver.create(application, targets)
                }.onFailure { error ->
                    Log.w(TAG, "酷狗下一首解析器校验失败", error)
                    reportNextTrackValidation(
                        valid = false,
                        detail = "resolver_validation:${error::class.java.simpleName}: ${error.message}",
                    )
                }.getOrNull() ?: return@post
                nextTrackResolver = resolver
                mainHandler.post(periodicNextTrackCapture)
                Log.i(TAG, "酷狗下一首解析器已启用")
            }
        }

        /**
         * 歌词文件拦截与下一首批量解析共享本进程唯一的 DexKit 吞吐；批量完成
         * 后再注册，避免酷狗刚更新后的首次冷启动出现两份全量 DEX 扫描并发
         * （参见 KUWO-STARTUP-001）。批量始终不完成时由超时兜底注册。
         */
        private fun onDexBatchResolved() {
            registerLyricFileHook()
        }

        private fun registerLyricFileHook() {
            if (!lyricHookRegistered.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(lyricHookTimeoutRegistration)
            host.hookAfterDexMethod(
                application = application,
                query = KuGouPluginEntry.lyricFileQuery(),
                callback = OfficialProviderMethodCallback { _, arguments ->
                    onLyricFileLoaded(arguments)
                },
            )
            Log.i(
                TAG,
                "酷狗歌词文件拦截已提交解析注册: class=$LYRIC_MANAGER_CLASS",
            )
        }

        /**
         * 旧版兜底：观察框架层文件读取入口。
         *
         * 旧版酷狗（如概念版 2.5.5）的歌词类完全混淆，且没有“文件路径入参”的加载方法，
         * 无法用 DexKit 语义锚定位；改为 Hook `java.io.FileInputStream` 的
         * `(File)` / `(String)` 构造器，按扩展名过滤出歌词文件后复用同一条解析链路。
         *
         * 回调位于 App 内所有文件打开的热路径上，因此过滤只做零分配的 endsWith 判断，
         * 命中后仍需通过轨道绑定与去重才进入解析。
         */
        fun installLegacyLyricFileWatch() {
            if (!legacyWatchRegistered.compareAndSet(false, true)) return
            val targets = listOf(
                OfficialProviderConstructorTarget(
                    className = FILE_INPUT_STREAM_CLASS,
                    parameterTypeNames = listOf("java.io.File"),
                ),
                OfficialProviderConstructorTarget(
                    className = FILE_INPUT_STREAM_CLASS,
                    parameterTypeNames = listOf("java.lang.String"),
                ),
            )
            var installed = 0
            targets.forEach { target ->
                runCatching {
                    host.hookAfterConstructor(target) { _, arguments ->
                        onLegacyLyricFileOpened(arguments)
                    }
                }.onSuccess {
                    installed++
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "酷狗旧版歌词文件兜底 Hook 安装失败: ${target.parameterTypeNames}",
                        error,
                    )
                }
            }
            Log.i(
                TAG,
                "酷狗旧版歌词文件兜底已安装: FileInputStream 构造器 count=$installed",
            )
        }

        private fun onLegacyLyricFileOpened(arguments: Array<Any?>?) {
            val raw = arguments?.firstOrNull()
            val path = when (raw) {
                is File -> raw.path
                is String -> raw
                else -> null
            } ?: return
            if (!KuGouLyricFilePathPolicy.isLyricFilePath(path)) return
            onLyricFilePath(path, dedupe = true)
        }

        private fun onLyricFileLoaded(arguments: Array<Any?>?) {
            onLyricFilePath(arguments?.firstOrNull() as? String, dedupe = false)
        }

        /**
         * 统一的歌词文件命中入口。
         *
         * @param dedupe 旧版框架层兜底传 true：同一次加载会观察到多次文件打开，
         * 且未绑定轨道时会放弃本次命中，去重必须放在“确认要解析”之后。
         */
        private fun onLyricFilePath(path: String?, dedupe: Boolean) {
            val value = path?.takeIf { it.isNotBlank() } ?: return
            val boundTrack = track
            if (!KuGouLyricFilePolicy.isBindableTrack(boundTrack)) {
                // 酷狗可能在 MediaSession 元数据广播前就加载歌词文件；此时无法
                // 可靠归属，放弃文件源（网络搜索兜底）也不冒错绑风险。
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "酷狗歌词文件命中但当前轨道身份为空，放弃绑定: $value")
                }
                return
            }
            if (dedupe && !legacyHitGuard.shouldHandle(value, SystemClock.elapsedRealtime())) {
                return
            }
            if (firstLyricFileHit.compareAndSet(false, true)) {
                Log.i(TAG, "酷狗歌词文件拦截首次命中: $value")
            } else if (BuildConfig.DEBUG) {
                Log.d(TAG, "酷狗歌词文件命中: $value")
            }
            executor.submit { loadLyricsFromFile(boundTrack, value) }
        }

        private fun loadLyricsFromFile(boundTrack: KuGouTrackMetadata, path: String) {
            val raw = try {
                val file = File(path)
                if (!file.isFile) return
                val length = file.length()
                if (length <= 0L || length > MAX_LYRIC_FILE_BYTES) return
                file.readBytes()
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "酷狗歌词文件读取失败: $path", error)
                }
                return
            }

            val parsed = decodeLyrics(raw, boundTrack.durationMs)
            if (parsed.isEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "酷狗歌词文件无可解析时间轴: $path")
                }
                return
            }
            val song = toSong(boundTrack, parsed)
            if (!KuGouLyricFilePolicy.isStillCurrent(boundTrack.identity, track.identity)) return
            fileSourceSong = song
            fileSourceIdentity = boundTrack.identity
            publish(song)
            if (BuildConfig.DEBUG) {
                val wordCount = parsed.sumOf { it.words.size }
                val translationCount = parsed.count { !it.translation.isNullOrBlank() }
                Log.i(
                    TAG,
                    "酷狗本地歌词文件已发布: lines=${parsed.size}, words=$wordCount, " +
                        "translations=$translationCount",
                )
            }
        }

        private fun captureNextTrack() {
            val current = track
            val resolver = nextTrackResolver ?: return
            var resolverFailed = false
            val resolvedNext = runCatching { resolver.resolve() }
                .onFailure { error ->
                    resolverFailed = true
                    Log.w(TAG, "酷狗下一首读取失败", error)
                    reportNextTrackValidation(
                        valid = false,
                        detail = "${error::class.java.simpleName}: ${error.message}",
                    )
                    deactivateNextTrackResolver()
                }
                .getOrNull()
            val candidateMatchesCurrent = resolvedNext?.let { candidate ->
                KuGouNextTrackCandidatePolicy.isCurrent(
                    currentTitle = current.title,
                    currentArtist = current.artist,
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.artist,
                )
            } == true
            val next = resolvedNext?.takeUnless { candidateMatchesCurrent }
            if (!resolverFailed) {
                if (candidateMatchesCurrent) {
                    if (nextTrackValidation.record(candidateMatchesCurrent = true)) {
                        reportNextTrackValidation(
                            valid = false,
                            detail = "candidate_matches_current:${resolvedNext?.id}",
                        )
                        deactivateNextTrackResolver()
                    }
                } else {
                    nextTrackValidation.record(candidateMatchesCurrent = false)
                    reportNextTrackValidation(
                        valid = true,
                        detail = "next=${next?.id ?: "none"}",
                    )
                }
            }
            if (candidateMatchesCurrent && BuildConfig.DEBUG) {
                Log.w(
                    TAG,
                    "酷狗下一首候选与当前曲一致，已清空: " +
                        "current=${current.title}/${current.artist}, " +
                        "candidate=${resolvedNext.title}/${resolvedNext.artist}",
                )
            }
            val frame = if (next == null || next.title.isBlank()) {
                OfficialProviderControlProtocol.encodeNextTrackClear(
                    currentId = current.identity,
                    currentTitle = current.title.orEmpty(),
                    currentArtist = current.artist.orEmpty(),
                )
            } else {
                OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = current.identity,
                    currentTitle = current.title.orEmpty(),
                    currentArtist = current.artist.orEmpty(),
                    nextId = next.id,
                    nextTitle = next.title,
                    nextArtist = next.artist,
                )
            }
            val now = SystemClock.elapsedRealtime()
            if (frame == lastNextTrackFrame &&
                now - lastNextTrackFrameSentAtMs < NEXT_TRACK_HEARTBEAT_MS
            ) {
                return
            }
            if (provider.player.sendText(frame)) {
                lastNextTrackFrame = frame
                lastNextTrackFrameSentAtMs = now
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "酷狗下一首控制帧已发送: " +
                            "current=${current.title}/${current.artist}, " +
                            "next=${next?.title}/${next?.artist}, id=${next?.id}",
                    )
                }
            }
        }

        private fun reportNextTrackValidation(valid: Boolean, detail: String) {
            if (valid && !BuildConfig.DEBUG) return
            nextTrackValidationKeys.forEach { key ->
                host.reportDexMethodValidation(key, valid, detail)
            }
        }

        private fun deactivateNextTrackResolver() {
            nextTrackResolver = null
            mainHandler.removeCallbacks(periodicNextTrackCapture)
        }

        private fun publish(song: Song) {
            if (lastSong == song) return
            lastSong = song
            provider.player.setSong(song)
        }

        private fun placeholder(track: KuGouTrackMetadata): Song = Song().apply {
            id = track.identity
            name = track.title
            artist = track.artist
            duration = track.durationMs
        }

        private fun toSong(track: KuGouTrackMetadata, lines: List<ParsedLine>): Song = Song().apply {
            id = track.identity
            name = track.title
            artist = track.artist
            duration = track.durationMs.takeIf { it > 0 } ?: lines.lastOrNull()?.end ?: 0L
            lyrics = lines.map { line ->
                RichLyricLine().apply {
                    begin = line.begin
                    end = line.end
                    duration = line.end - line.begin
                    text = line.text
                    translation = line.translation
                    words = line.words.takeIf(List<LyricWord>::isNotEmpty)
                }
            }
        }

        private fun decodeLyrics(raw: ByteArray, durationMs: Long): List<ParsedLine> {
            val text = if (raw.hasKrcHeader()) {
                KrcDecryptor.decrypt(raw) ?: return emptyList()
            } else {
                // 酷狗本地歌词缓存存在没有 "krc1" 魔数的形态，先按 KRC 解密，
                // 失败再按纯文本处理
                KrcDecryptor.decrypt(raw)
                    ?: raw.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            }
            val lines = KrcLyricsParser.parse(text).takeIf(List<ParsedLine>::isNotEmpty)
                ?: LrcLyricsParser.parse(text, durationMs)
            val translations = KuGouLanguageParser.translations(text, lines.size)
            return if (translations == null) {
                lines
            } else {
                lines.mapIndexed { index, line ->
                    line.copy(translation = translations[index])
                }
            }
        }

        private fun isCurrent(
            requestGeneration: Long,
            requestTrack: KuGouTrackMetadata,
        ): Boolean = !Thread.currentThread().isInterrupted &&
            requestGeneration == generation.get() &&
            requestTrack.identity == track.identity

        private fun loadCached(downloadId: String): ByteArray? {
            val file = cacheFile(downloadId)
            if (!file.isFile) return null
            return runCatching { file.readBytes() }
                .onFailure { error ->
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "酷狗歌词缓存读取失败: id=$downloadId", error)
                    }
                }
                .getOrNull()
        }

        private fun writeCache(downloadId: String, raw: ByteArray) {
            runCatching { cacheFile(downloadId).writeBytes(raw) }
                .onFailure { error ->
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "酷狗歌词缓存写入失败: id=$downloadId", error)
                    }
                }
        }

        private fun cacheFile(downloadId: String): File =
            File(cacheDir, "${sha256Hex(downloadId)}.lyrics")

        private fun ByteArray.hasKrcHeader(): Boolean = size >= 4 &&
            this[0] == 'k'.code.toByte() &&
            this[1] == 'r'.code.toByte() &&
            this[2] == 'c'.code.toByte() &&
            this[3] == '1'.code.toByte()
    }

    private data class NextTrack(
        val id: String,
        val title: String,
        val artist: String,
    )

    private class KuGouNextTrackResolver private constructor(
        private val singletonMethod: Method,
        private val nextMediaMethod: Method,
    ) {
        @Volatile
        private var mediaAccessors: MediaAccessors? = null

        fun resolve(): NextTrack? {
            val manager = singletonMethod.invoke(null) ?: return null
            val media = nextMediaMethod.invoke(manager) ?: return null
            val accessors = mediaAccessors
                ?.takeIf { it.ownerClass.isInstance(media) }
                ?: MediaAccessors.create(media.javaClass).also { mediaAccessors = it }
            return NextTrack(
                id = accessors.hashMethod.invoke(media) as? String ?: "",
                title = accessors.titleMethod.invoke(media) as? String ?: "",
                artist = accessors.artistMethod.invoke(media) as? String ?: "",
            )
        }

        private data class MediaAccessors(
            val ownerClass: Class<*>,
            val hashMethod: Method,
            val titleMethod: Method,
            val artistMethod: Method,
        ) {
            companion object {
                fun create(ownerClass: Class<*>): MediaAccessors = MediaAccessors(
                    ownerClass = ownerClass,
                    hashMethod = ownerClass.getMethod("getHashValue").apply { isAccessible = true },
                    titleMethod = ownerClass.getMethod("getTrackName").apply { isAccessible = true },
                    artistMethod = ownerClass.getMethod("getArtistName").apply { isAccessible = true },
                )
            }
        }

        companion object {
            fun create(
                application: Application,
                targets: List<OfficialProviderMethodTarget>,
            ): KuGouNextTrackResolver {
                require(targets.size == 2) { "酷狗下一首目标数量错误" }
                val loader = application.classLoader
                val singletonMethod = targets[0].toMethod(loader)
                val nextMediaMethod = targets[1].toMethod(loader)
                require(Modifier.isStatic(singletonMethod.modifiers))
                require(!Modifier.isStatic(nextMediaMethod.modifiers))
                return KuGouNextTrackResolver(
                    singletonMethod = singletonMethod,
                    nextMediaMethod = nextMediaMethod,
                )
            }

            private fun OfficialProviderMethodTarget.toMethod(loader: ClassLoader): Method {
                val clazz = loader.loadClass(className)
                val parameters = parameterTypeNames.map { name ->
                    when (name) {
                        "boolean" -> Boolean::class.javaPrimitiveType!!
                        "int" -> Int::class.javaPrimitiveType!!
                        "long" -> Long::class.javaPrimitiveType!!
                        else -> loader.loadClass(name)
                    }
                }.toTypedArray()
                return clazz.getDeclaredMethod(methodName, *parameters).apply {
                    isAccessible = true
                }
            }
        }
    }

    private data class ParsedLine(
        val begin: Long,
        val end: Long,
        val text: String,
        val words: List<LyricWord> = emptyList(),
        val translation: String? = null,
    )

    internal object KrcDecryptor {
        private val key = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
            206.toByte(), 210.toByte(), 110, 105,
        )
        private val magic = "krc1".toByteArray()

        fun decrypt(input: ByteArray): String? = runCatching {
            // 网络下载的 KRC 带 "krc1" 魔数；本地缓存文件两种形态都存在，
            // 按魔数自动选择解密偏移
            val offset = if (input.hasKrcMagic()) magic.size else 0
            require(input.size > offset)
            val decoded = ByteArray(input.size - offset) { index ->
                (input[index + offset].toInt() xor key[index % key.size].toInt()).toByte()
            }
            java.util.zip.InflaterInputStream(decoded.inputStream()).bufferedReader().use { it.readText() }
        }.getOrNull()

        private fun ByteArray.hasKrcMagic(): Boolean = size >= magic.size &&
            magic.indices.all { this[it] == magic[it] }
    }

    private object KrcLyricsParser {
        private val linePattern = Regex("^\\[(\\d+)\\s*,\\s*(\\d+)](.*)$")
        private val wordPattern = Regex("<(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)>")

        fun parse(content: String): List<ParsedLine> = content.lineSequence().mapNotNull { raw ->
            val match = linePattern.matchEntire(raw.trim()) ?: return@mapNotNull null
            val begin = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val body = match.groupValues[3]
            val tags = wordPattern.findAll(body).toList()
            if (tags.isEmpty()) {
                return@mapNotNull ParsedLine(begin, begin + duration, body.trim())
            }
            val words = buildList {
                tags.forEachIndexed { index, tag ->
                    val start = tag.range.last + 1
                    val end = tags.getOrNull(index + 1)?.range?.first ?: body.length
                    val text = body.substring(start, end)
                    val offset = tag.groupValues[1].toLongOrNull() ?: 0L
                    val wordDuration = tag.groupValues[2].toLongOrNull() ?: 0L
                    add(
                        LyricWord(
                            begin = begin + offset,
                            end = begin + offset + wordDuration,
                            duration = wordDuration,
                            text = text,
                        )
                    )
                }
            }
            ParsedLine(begin, begin + duration, words.joinToString("") { it.text.orEmpty() }, words)
        }.toList().sortedBy(ParsedLine::begin)
    }

    private object LrcLyricsParser {
        private val timestamp = Regex("\\[(\\d{1,3})[:.]([0-5]\\d)(?:[:.]([0-9]{1,3}))?]")

        fun parse(content: String, duration: Long): List<ParsedLine> {
            val rows = content.lineSequence().flatMap { raw ->
                val matches = timestamp.findAll(raw).toList()
                if (matches.isEmpty() || matches.first().range.first != 0) {
                    emptySequence()
                } else {
                    val text = raw.substring(matches.last().range.last + 1).trim()
                    matches.asSequence().map { match ->
                        val minute = match.groupValues[1].toLongOrNull() ?: 0L
                        val second = match.groupValues[2].toLongOrNull() ?: 0L
                        val fraction = match.groupValues.getOrNull(3).orEmpty()
                        val millis = when (fraction.length) {
                            1 -> fraction.toLong() * 100
                            2 -> fraction.toLong() * 10
                            3 -> fraction.toLong()
                            else -> 0L
                        }
                        minute * 60_000 + second * 1_000 + millis to text
                    }
                }
            }.toList().sortedBy { it.first }
            return rows.mapIndexed { index, (begin, text) ->
                val end = rows.getOrNull(index + 1)?.first
                    ?: duration.takeIf { it > begin } ?: begin + 5_000L
                ParsedLine(begin, end, text)
            }
        }
    }

}

/**
 * 本地歌词文件源（酷狗自读的精确匹配）与 v2 搜索源（模糊匹配）之间的
 * 优先级与归属规则。文件源命中同曲后拥有更高优先级，但不回收已发布的
 * 搜索结果以外的任何内容；归属以钩子命中时刻的轨道身份为准。
 */
internal object KuGouLyricFilePolicy {
    /** 元数据尚未到达（mediaId 与标题都为空）时文件命中无法可靠归属。 */
    fun isBindableTrack(track: KuGouTrackMetadata): Boolean =
        !track.mediaId.isNullOrBlank() || !track.title.isNullOrBlank()

    /** 归属身份与当前轨道不一致（命中后已切歌）时丢弃解析结果，防止错绑。 */
    fun isStillCurrent(boundIdentity: String, currentIdentity: String): Boolean =
        boundIdentity == currentIdentity

    /** 本地文件源已发布该轨道时，搜索不再启动、已完成的搜索结果也不覆盖。 */
    fun ownsTrack(fileSourceIdentity: String?, identity: String): Boolean =
        fileSourceIdentity == identity
}

internal object KuGouNextTrackCandidatePolicy {
    private val whitespace = Regex("\\s+")

    fun isCurrent(
        currentTitle: String?,
        currentArtist: String?,
        candidateTitle: String,
        candidateArtist: String,
    ): Boolean {
        val normalizedCurrentTitle = normalize(currentTitle)
        if (normalizedCurrentTitle.isEmpty()) return false
        return normalizedCurrentTitle == normalize(candidateTitle) &&
            normalize(currentArtist) == normalize(candidateArtist)
    }

    private fun normalize(value: String?): String = value
        .orEmpty()
        .trim()
        .replace(whitespace, " ")
        .lowercase(Locale.ROOT)
}

internal class KuGouNextTrackValidationTracker(
    private val invalidThreshold: Int = 3,
) {
    private var consecutiveCurrentCandidates = 0

    init {
        require(invalidThreshold > 0)
    }

    @Synchronized
    fun record(candidateMatchesCurrent: Boolean): Boolean {
        if (!candidateMatchesCurrent) {
            consecutiveCurrentCandidates = 0
            return false
        }
        consecutiveCurrentCandidates += 1
        if (consecutiveCurrentCandidates < invalidThreshold) return false
        consecutiveCurrentCandidates = 0
        return true
    }

    @Synchronized
    fun reset() {
        consecutiveCurrentCandidates = 0
    }
}
