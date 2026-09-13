/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * QQ Music mobile exposes lyric playback metadata from QQPlayerService while
 * QQ Music HD keeps both lyric and queue state in its main process. Both apps
 * share the same lyric backend and Pack, with package-specific runtime routing.
 */

package com.juren233.hle.providers.qqmusic

import android.app.Application
import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hle.providers.qqmusic.BuildConfig
import com.genius.hyperlyrics.provider.OfficialProviderControlProtocol
import com.genius.hyperlyrics.provider.OfficialProviderDexMethodsCallback
import com.genius.hyperlyrics.provider.OfficialProviderHost
import com.genius.hyperlyrics.provider.OfficialProviderMetadataCallback
import com.genius.hyperlyrics.provider.OfficialProviderPlaybackStateCallback
import com.genius.hyperlyrics.provider.OfficialProviderPlugin
import com.juren233.hle.providers.qqmusic.qrc.QqQrcDecrypter
import com.juren233.hle.providers.qqmusic.qrc.QqQrcLine
import com.juren233.hle.providers.qqmusic.qrc.QqQrcParser
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object QQMusicPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/QQMusic"
    private const val PROVIDER_PACKAGE = "com.genius.hyperlyrics.provider.qqmusic"
    private val installed = AtomicBoolean(false)

    @Volatile
    private var runtime: QQRuntime? = null

    @Volatile
    private var nextTrackRuntime: QQNextTrackRuntime? = null

    @Volatile
    private var bufferRuntime: QQBufferRuntime? = null

    override fun install(host: OfficialProviderHost) {
        require(QQMusicRuntimePlan.supports(host.packageName)) {
            "Unsupported QQ Music package: ${host.packageName}"
        }
        host.hookApplication { application ->
            val processName = Application.getProcessName()
            val features = QQMusicRuntimePlan.resolve(host.packageName, processName)
            if (features.isEmpty()) return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication
            var lyricRuntime: QQRuntime? = null
            if (QQMusicRuntimeFeature.LYRICS in features) {
                lyricRuntime = QQRuntime(application, host.packageName).also { it.start() }
            }
            if (QQMusicRuntimeFeature.BUFFERING_STATE in features && lyricRuntime != null) {
                QQBufferRuntime(application, host, lyricRuntime).also {
                    it.start()
                    bufferRuntime = it
                }
            }
            if (QQMusicRuntimeFeature.NEXT_TRACK in features) {
                QQNextTrackRuntime(application, host.packageName, host).start()
            }
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                runtime?.onPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                runtime?.onMetadata(metadata)
            },
        )
        Log.i(TAG, "QQ 音乐 Provider Hook 已安装: package=${host.packageName}")
    }

    private class QQRuntime(
        private val application: Application,
        private val playerPackage: String,
    ) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-QQMusic-Lyrics").apply { isDaemon = true }
        }
        private val trackCoordinator = QQMusicLyricTrackCoordinator(playerPackage)
        private val bufferCoordinator = QQMusicBufferStateCoordinator()
        private val cacheDir = File(application.filesDir, "hle-provider/qqmusic")
        private var activeLoadKey: String? = null
        private var lastSong: Song? = null
        private var lastMetadataId: String? = null
        private var latestPlaybackState: PlaybackState? = null

        @Volatile
        var provider: LyriconProvider? = null
            private set

        fun start() {
            cacheDir.mkdirs()
            provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = playerPackage,
            ).also {
                it.register()
                refreshDisplayPreference(it)
            }
            runtime = this
            Log.i(TAG, "QQ 音乐 Lyricon Provider 已注册: process=${Application.getProcessName()}")
        }

        @Synchronized
        fun onMetadata(value: MediaMetadata?) {
            val id = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim()
                ?.takeIf(String::isNotEmpty) ?: return
            if (lastMetadataId != id) {
                lastMetadataId = id
                bufferCoordinator.reset()
            }
            refreshDisplayPreference(provider)
            applyTrackDecision(
                trackCoordinator.onMetadata(
                    QQMusicLyricTrack(
                        id = id,
                        title = value.getString(MediaMetadata.METADATA_KEY_TITLE),
                        artist = value.getString(MediaMetadata.METADATA_KEY_ARTIST),
                        duration = value.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    ),
                ),
            )
        }

        @Synchronized
        fun onPlaybackState(state: PlaybackState?) {
            latestPlaybackState = state
            when (val decision = bufferCoordinator.onPlaybackState(state?.toSnapshot())) {
                QQMusicPlaybackDecision.Forward -> provider?.player?.setPlaybackState(state)
                QQMusicPlaybackDecision.Ignore -> Unit
                is QQMusicPlaybackDecision.Publish -> publishSyntheticPlaybackState(
                    decision.state,
                    reason = "media_session_while_buffering",
                )
            }
        }

        @Synchronized
        fun onBufferStarted() {
            when (val decision = bufferCoordinator.onBufferStarted(SystemClock.elapsedRealtime())) {
                is QQMusicPlaybackDecision.Publish -> publishSyntheticPlaybackState(
                    decision.state,
                    reason = "buffer_started",
                )
                QQMusicPlaybackDecision.Forward,
                QQMusicPlaybackDecision.Ignore,
                -> Unit
            }
        }

        @Synchronized
        fun onBufferEnded() {
            when (val decision = bufferCoordinator.onBufferEnded(SystemClock.elapsedRealtime())) {
                is QQMusicPlaybackDecision.Publish -> publishSyntheticPlaybackState(
                    decision.state,
                    reason = "buffer_ended",
                )
                QQMusicPlaybackDecision.Forward,
                QQMusicPlaybackDecision.Ignore,
                -> Unit
            }
        }

        private fun PlaybackState.toSnapshot() = QQMusicPlaybackSnapshot(
            state = state,
            position = position,
            updatedAtMs = lastPositionUpdateTime,
            speed = playbackSpeed,
        )

        private fun publishSyntheticPlaybackState(
            snapshot: QQMusicPlaybackSnapshot,
            reason: String,
        ) {
            val builder = latestPlaybackState?.let(PlaybackState::Builder) ?: PlaybackState.Builder()
            val state = builder.setState(
                snapshot.state,
                snapshot.position,
                snapshot.speed,
                snapshot.updatedAtMs,
            ).build()
            val result = provider?.player?.setPlaybackState(state)
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "QQ 缓冲状态已发布: reason=$reason state=${snapshot.state}, " +
                        "position=${snapshot.position}, updatedAt=${snapshot.updatedAtMs}, result=$result",
                )
            }
        }

        @Synchronized
        fun onQueueSnapshot(snapshot: QQMusicQueueSnapshot?) {
            applyTrackDecision(trackCoordinator.onQueueSnapshot(snapshot))
        }

        private fun applyTrackDecision(decision: QQMusicLyricTrackDecision) {
            when (decision) {
                is QQMusicLyricTrackDecision.AwaitingVerifiedId -> {
                    activeLoadKey = null
                    if (BuildConfig.DEBUG && playerPackage == QQMusicRuntimePlan.HD_PACKAGE) {
                        Log.i(
                            TAG,
                            "QQ HD 歌词等待真实歌曲 ID: " +
                                "mediaId=${decision.track.id}, title=${decision.track.title}",
                        )
                    }
                    publish(placeholder(decision.track))
                }
                is QQMusicLyricTrackDecision.Load -> {
                    if (BuildConfig.DEBUG && playerPackage == QQMusicRuntimePlan.HD_PACKAGE) {
                        Log.i(
                            TAG,
                            "QQ HD 歌词使用 SongInfo 歌曲 ID: " +
                                "songId=${decision.track.id}, title=${decision.track.title}",
                        )
                    }
                    load(decision.track)
                }
                QQMusicLyricTrackDecision.Unchanged -> Unit
            }
        }

        private fun load(track: QQMusicLyricTrack) {
            val loadKey = track.loadKey()
            activeLoadKey = loadKey
            publish(loadCached(track) ?: placeholder(track))
            executor.execute {
                val songId = runCatching { QQMusicSongMidResolver.resolveNumericSongId(track.id) }
                    .onFailure { error ->
                        Log.w(TAG, "QQ 歌曲 ID 换算失败: id=${track.id}", error)
                    }
                    .getOrNull()
                    ?: return@execute
                runCatching { QQClient.fetch(songId) }
                    .onSuccess { payload ->
                        writeCache(track.id, payload)
                        synchronized(this@QQRuntime) {
                            if (activeLoadKey == loadKey) publish(toSong(track, payload))
                        }
                    }
                    .onFailure { error ->
                        Log.w(TAG, "QQ 歌词下载失败: id=${track.id} songId=$songId", error)
                    }
            }
        }

        private fun QQMusicLyricTrack.loadKey(): String = "$id\u0000$title\u0000$artist"

        private fun refreshDisplayPreference(provider: LyriconProvider?) {
            val target = provider ?: return
            val prefs = application.getSharedPreferences("qqmusicplayer", Context.MODE_PRIVATE)
            target.player.setDisplayTranslation(prefs.getBoolean("showtranslyric", false))
            target.player.setDisplayRoma(prefs.getBoolean("showromalyric", false))
        }

        private fun publish(song: Song) {
            if (lastSong == song) return
            lastSong = song
            provider?.player?.setSong(song)
        }

        private fun placeholder(track: QQMusicLyricTrack): Song = Song().apply {
            id = track.id
            name = track.title
            artist = track.artist
            duration = track.duration
        }

        private fun loadCached(track: QQMusicLyricTrack): Song? {
            val file = File(cacheDir, "${track.id}.json")
            if (!file.isFile) return null
            return runCatching { toSong(track, QQPayload.fromJson(JSONObject(file.readText()))) }.getOrNull()
        }

        private fun writeCache(id: String, payload: QQPayload) {
            runCatching { File(cacheDir, "$id.json").writeText(payload.toJson().toString()) }
                .onFailure { Log.w(TAG, "QQ 歌词缓存写入失败: id=$id", it) }
        }
    }

    private class QQBufferRuntime(
        private val application: Application,
        private val host: OfficialProviderHost,
        private val lyricRuntime: QQRuntime,
    ) {
        private val firstStartCallback = AtomicBoolean(false)
        private val firstEndCallback = AtomicBoolean(false)

        fun start() {
            val queries = QQMusicBufferHookResolver.queries(application) ?: return
            queries.forEach { query ->
                host.hookAfterDexMethod(
                    application = application,
                    query = query,
                    callback = com.genius.hyperlyrics.provider.OfficialProviderMethodCallback {
                            _, _ ->
                        when (query.cacheKey) {
                            QQMusicBufferHookResolver.BUFFER_START_CACHE_KEY -> {
                                if (BuildConfig.DEBUG && firstStartCallback.compareAndSet(false, true)) {
                                    Log.i(TAG, "QQ 缓冲开始 Hook 首次命中")
                                }
                                lyricRuntime.onBufferStarted()
                            }
                            QQMusicBufferHookResolver.BUFFER_END_CACHE_KEY -> {
                                if (BuildConfig.DEBUG && firstEndCallback.compareAndSet(false, true)) {
                                    Log.i(TAG, "QQ 缓冲结束 Hook 首次命中")
                                }
                                lyricRuntime.onBufferEnded()
                            }
                        }
                    },
                )
            }
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "QQ 缓冲边界 Hook 已请求安装: queries=${queries.size}")
            }
        }
    }

    private class QQNextTrackRuntime(
        private val application: Application,
        private val playerPackage: String,
        private val host: OfficialProviderHost,
    ) {
        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "HLE-QQMusic-NextTrack").apply { isDaemon = true }
        }
        private var lastFrame: String? = null
        private var lastFrameSentAtMs = 0L
        private val mainHandler = Handler(Looper.getMainLooper())
        private val resolverGeneration = AtomicLong(0L)

        @Volatile
        private var pollingTask: ScheduledFuture<*>? = null

        @Volatile
        private var nextTrackValidationKeys: List<String> = emptyList()

        @Volatile
        private var provider: LyriconProvider? = null

        fun start() {
            val queries = QQMusicNextTrackResolver.queries(application)
            if (queries == null) {
                Log.w(TAG, "QQ 音乐包名不受支持，跳过下一首适配")
                return
            }
            nextTrackValidationKeys = queries.map { it.cacheKey }
            host.resolveDexMethods(
                application = application,
                queries = queries,
                callback = OfficialProviderDexMethodsCallback { targets ->
                    mainHandler.post { startResolved(targets) }
                },
            )
        }

        @Synchronized
        private fun startResolved(
            targets: List<com.genius.hyperlyrics.provider.OfficialProviderMethodTarget>,
        ) {
            val resolverResult = runCatching {
                QQMusicNextTrackResolver.create(application, targets)
            }
            val resolver = resolverResult.getOrElse { error ->
                Log.w(TAG, "QQ 音乐下一首解析器校验失败", error)
                reportNextTrackValidation(
                    valid = false,
                    detail = "resolver_validation:${error::class.java.simpleName}: ${error.message}",
                )
                return
            }
            val sharedProvider = runtime?.provider
            provider = sharedProvider ?: LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = playerPackage,
            ).also { it.register() }
            nextTrackRuntime = this
            pollingTask?.cancel(false)
            val generation = resolverGeneration.incrementAndGet()
            pollingTask = scheduler.scheduleWithFixedDelay(
                { capture(resolver, generation) },
                0L,
                NEXT_TRACK_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            Log.i(
                TAG,
                "QQ 音乐下一首 Provider ${if (sharedProvider == null) "已注册" else "已复用"}: " +
                    "process=${Application.getProcessName()}",
            )
        }

        private fun capture(resolver: QQMusicNextTrackResolver, generation: Long) {
            if (resolverGeneration.get() != generation) return
            runCatching {
                val snapshot = resolver.resolve()
                if (resolverGeneration.get() != generation) return
                runtime?.onQueueSnapshot(snapshot)
                publish(snapshot)
                snapshot
            }
                .onSuccess { snapshot ->
                    if (resolverGeneration.get() != generation) return@onSuccess
                    reportNextTrackValidation(
                        valid = true,
                        detail = "next=${snapshot?.next?.id ?: "none"}",
                    )
                }
                .onFailure { error ->
                    if (resolverGeneration.get() != generation) return@onFailure
                    reportNextTrackValidation(
                        valid = false,
                        detail = "${error::class.java.simpleName}: ${error.message}",
                    )
                    stopPolling(generation)
                    if (BuildConfig.DEBUG) Log.w(TAG, "QQ 音乐下一首采集失败", error)
                }
        }

        private fun reportNextTrackValidation(valid: Boolean, detail: String) {
            if (valid && !BuildConfig.DEBUG) return
            nextTrackValidationKeys.forEach { key ->
                host.reportDexMethodValidation(key, valid, detail)
            }
        }

        @Synchronized
        private fun stopPolling(generation: Long) {
            if (resolverGeneration.get() != generation) return
            resolverGeneration.incrementAndGet()
            pollingTask?.cancel(false)
            pollingTask = null
        }

        private fun publish(snapshot: QQMusicQueueSnapshot?) {
            val frame = when {
                snapshot == null -> OfficialProviderControlProtocol.encodeNextTrackClear()
                snapshot.next == null || snapshot.next.title.isBlank() ->
                    OfficialProviderControlProtocol.encodeNextTrackClear(
                        currentId = snapshot.current.id,
                        currentTitle = snapshot.current.title,
                        currentArtist = snapshot.current.artist,
                    )
                else -> OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = snapshot.current.id,
                    currentTitle = snapshot.current.title,
                    currentArtist = snapshot.current.artist,
                    nextId = snapshot.next.id,
                    nextTitle = snapshot.next.title,
                    nextArtist = snapshot.next.artist,
                )
            }
            val now = SystemClock.elapsedRealtime()
            if (frame == lastFrame && now - lastFrameSentAtMs < NEXT_TRACK_HEARTBEAT_MS) return
            if (provider?.player?.sendText(frame) == true) {
                lastFrame = frame
                lastFrameSentAtMs = now
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "QQ 下一首控制帧已发送: current=${snapshot?.current?.id}, " +
                            "next=${snapshot?.next?.id}",
                    )
                }
            }
        }
    }

    private const val NEXT_TRACK_POLL_INTERVAL_MS = 1_500L
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L

    private data class QQPayload(
        val lyric: String?,
        val translation: String?,
        val roma: String?,
    ) {
        fun toJson() = JSONObject().apply {
            putOpt("lyric", lyric)
            putOpt("translation", translation)
            putOpt("roma", roma)
        }

        companion object {
            fun fromJson(json: JSONObject) = QQPayload(
                lyric = json.optString("lyric").takeIf(String::isNotBlank),
                translation = json.optString("translation").takeIf(String::isNotBlank),
                roma = json.optString("roma").takeIf(String::isNotBlank),
            )
        }
    }

    private object QQClient {
        private const val URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"

        fun fetch(id: String): QQPayload {
            val connection = (URI(URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://y.qq.com/")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            return try {
                val body = listOf(
                    "version" to "15",
                    "miniversion" to "100",
                    // lrctype=4 is the QQ Music QRC response. It carries word
                    // timings in the original lyric and the translation used
                    // by the QQ Music client; QqQrcDecrypter transparently
                    // leaves ordinary LRC values unchanged.
                    "lrctype" to "4",
                    "musicid" to id,
                ).joinToString("&") { (key, value) ->
                    "$key=${URLEncoder.encode(value, "UTF-8")}"
                }
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "QQ 音乐 HTTP ${connection.responseCode}"
                }
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                QQPayload(
                    lyric = QqQrcDecrypter.decode(cdata(raw, "content")),
                    translation = QqQrcDecrypter.decode(cdata(raw, "contentts")),
                    roma = QqQrcDecrypter.decode(cdata(raw, "contentroma")),
                )
            } finally {
                connection.disconnect()
            }
        }

        private fun cdata(raw: String, name: String): String? {
            val pattern = Regex("<$name(?:\\s|>)[^>]*>.*?<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
            return pattern.find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
        }
    }

    private data class TimelineLine(
        val begin: Long,
        val end: Long,
        val text: String,
        val words: List<LyricWord> = emptyList(),
    )

    private object LrcParser {
        private val timestamp = Regex("\\[(\\d{1,3})[:.]([0-5]\\d)(?:[:.]([0-9]{1,3}))?]")

        fun parse(raw: String?): List<TimelineLine> {
            if (raw.isNullOrBlank()) return emptyList()
            val parsed = mutableListOf<TimelineLine>()
            raw.lineSequence().forEach { line ->
                val matches = timestamp.findAll(line).toList()
                if (matches.isEmpty() || matches.first().range.first != 0) return@forEach
                val content = line.substring(matches.last().range.last + 1).trim()
                matches.forEach { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fraction = match.groupValues.getOrNull(3).orEmpty()
                    val millis = when (fraction.length) {
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        3 -> fraction.toLong()
                        else -> 0L
                    }
                    parsed += TimelineLine(minutes * 60_000 + seconds * 1_000 + millis, 0L, content)
                }
            }
            val sorted = parsed.sortedBy(TimelineLine::begin)
            return sorted.mapIndexed { index, line ->
                line.copy(end = sorted.getOrNull(index + 1)?.begin ?: line.begin + 5_000L)
            }
        }
    }

    private fun toSong(track: QQMusicLyricTrack, payload: QQPayload): Song {
        val source = QqQrcParser.parse(payload.lyric).map { it.toTimelineLine() }
            .ifEmpty { LrcParser.parse(payload.lyric) }
        val translations = LrcParser.parse(payload.translation).ifEmpty {
            QqQrcParser.parse(payload.translation).map { it.toTimelineLine() }
        }
        val romas = LrcParser.parse(payload.roma).ifEmpty {
            QqQrcParser.parse(payload.roma).map { it.toTimelineLine() }
        }
        val rich = source.map { line ->
            RichLyricLine().apply {
                begin = line.begin
                end = line.end
                duration = (line.end - line.begin).coerceAtLeast(0L)
                text = line.text
                words = line.words.takeIf(List<LyricWord>::isNotEmpty)
                translation = closest(translations, line.begin)?.text
                    ?.takeUnless { it.trim() == "//" }
                roma = closest(romas, line.begin)?.text
            }
        }
        return Song().apply {
            id = track.id
            name = track.title
            artist = track.artist
            duration = track.duration.takeIf { it > 0 } ?: rich.lastOrNull()?.end ?: 0L
            lyrics = rich.takeIf { it.isNotEmpty() }
        }
    }

    private fun closest(lines: List<TimelineLine>, position: Long): TimelineLine? = lines
        .minByOrNull { kotlin.math.abs(it.begin - position) }
        ?.takeIf { kotlin.math.abs(it.begin - position) <= 1_000L }

    private fun QqQrcLine.toTimelineLine() = TimelineLine(
        begin = begin,
        end = end,
        text = text,
        words = words,
    )
}

internal enum class QQMusicRuntimeFeature {
    LYRICS,
    BUFFERING_STATE,
    NEXT_TRACK,
}

internal object QQMusicRuntimePlan {
    const val MOBILE_PACKAGE = "com.tencent.qqmusic"
    const val HD_PACKAGE = "com.tencent.qqmusicpad"
    const val MIUI_PACKAGE = "com.miui.player"
    private const val MOBILE_PLAYER_PROCESS = "$MOBILE_PACKAGE:QQPlayerService"
    private const val MIUI_REMOTE_PROCESS = "$MIUI_PACKAGE:remote"

    fun supports(packageName: String): Boolean =
        packageName == MOBILE_PACKAGE || packageName == HD_PACKAGE || packageName == MIUI_PACKAGE

    fun resolve(packageName: String, processName: String): Set<QQMusicRuntimeFeature> = when {
        packageName == MOBILE_PACKAGE && processName == MOBILE_PACKAGE ->
            setOf(QQMusicRuntimeFeature.NEXT_TRACK)
        packageName == MOBILE_PACKAGE && processName == MOBILE_PLAYER_PROCESS ->
            setOf(QQMusicRuntimeFeature.LYRICS, QQMusicRuntimeFeature.BUFFERING_STATE)
        packageName == HD_PACKAGE && processName == HD_PACKAGE ->
            setOf(QQMusicRuntimeFeature.LYRICS, QQMusicRuntimeFeature.NEXT_TRACK)
        packageName == MIUI_PACKAGE && processName == MIUI_PACKAGE ->
            setOf(QQMusicRuntimeFeature.NEXT_TRACK)
        packageName == MIUI_PACKAGE && processName == MIUI_REMOTE_PROCESS ->
            setOf(QQMusicRuntimeFeature.LYRICS)
        else -> emptySet()
    }
}
