/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified for the HLE Provider Pack runtime.
 */

package com.juren233.hle.providers.kuwo

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.genius.hyperlyrics.provider.OfficialProviderControlProtocol
import com.genius.hyperlyrics.provider.OfficialProviderDexMethodsCallback
import com.genius.hyperlyrics.provider.OfficialProviderHost
import com.genius.hyperlyrics.provider.OfficialProviderMetadataCallback
import com.genius.hyperlyrics.provider.OfficialProviderPlaybackStateCallback
import com.genius.hyperlyrics.provider.OfficialProviderPlugin
import com.genius.hyperlyrics.provider.OfficialProviderMethodTarget
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object KuwoPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/Kuwo"
    private const val TARGET_PACKAGE = "cn.kuwo.player"
    private const val PROVIDER_PACKAGE =
        "com.genius.hyperlyrics.provider.kuwo"

    private val installed = AtomicBoolean(false)

    @Volatile
    private var runtime: KuwoRuntime? = null

    override fun install(host: OfficialProviderHost) {
        require(host.packageName == TARGET_PACKAGE) {
            "Unexpected target package: ${host.packageName}"
        }
        host.hookApplication { application ->
            if (Application.getProcessName() != host.packageName) return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication
            KuwoRuntime(application, host.packageName, host).start()
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                runtime?.onPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                runtime?.onMetadata(metadata)
            },
        )
        Log.i(TAG, "酷我音乐 Provider Hook 已安装")
    }

    private class KuwoRuntime(
        private val application: Application,
        private val playerPackage: String,
        private val host: OfficialProviderHost,
    ) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-Kuwo-Lyrics").apply { isDaemon = true }
        }
        private val cacheDir = File(application.filesDir, "hle-provider/kuwo")
        private val mainHandler = Handler(Looper.getMainLooper())

        private val requestGuard = KuwoRequestGuard()
        private val firstNextTrackHit = AtomicBoolean(false)
        private val nextTrackSetupStarted = AtomicBoolean(false)

        private var lastSong: Song? = null
        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L
        private var nextTrackResolver: KuwoNextTrackResolver? = null

        @Volatile
        private var nextTrackValidationKeys: List<String> = emptyList()

        @Volatile
        private var currentTrack: KuwoTrackMetadata? = null

        private val requestedNextTrackCapture = Runnable(::captureNextTrack)
        private val periodicNextTrackCapture = object : Runnable {
            override fun run() {
                captureNextTrack()
                if (nextTrackResolver != null) {
                    mainHandler.postDelayed(this, NEXT_TRACK_POLL_INTERVAL_MS)
                }
            }
        }
        private val nextTrackValidation = KuwoNextTrackValidationTracker()

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
                it.player.setDisplayTranslation(true)
                it.player.setDisplayRoma(false)
                it.register()
            }
            runtime = this
            Log.i(TAG, "酷我音乐 Lyricon Provider 已注册: process=${Application.getProcessName()}")
        }

        fun onPlaybackState(state: PlaybackState?) {
            provider?.player?.setPlaybackState(state)
            if (state?.state == PlaybackState.STATE_PLAYING) {
                ensureNextTrackCaptureStarted()
            }
        }

        fun onMetadata(value: MediaMetadata?) {
            if (value == null) {
                currentTrack = null
                requestGuard.clear()
                requestNextTrackCapture()
                return
            }
            val track = KuwoTrackMetadata(
                mediaId = value.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = value.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = value.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            if (track.mediaId.isNullOrBlank() && track.title.isNullOrBlank()) return
            currentTrack = track
            ensureNextTrackCaptureStarted()
            requestNextTrackCapture()

            val directRid = KuwoTrackIdResolver.directRid(track.mediaId)
            val requestKey = directRid?.let { "rid:$it" } ?: track.stableSearchKey()
            if (!requestGuard.select(requestKey)) return
            publish(placeholder(track, directRid, requestKey))

            executor.execute {
                loadTrack(requestKey, track, directRid)
            }
        }

        private fun ensureNextTrackCaptureStarted() {
            if (nextTrackSetupStarted.compareAndSet(false, true)) {
                mainHandler.post(::startNextTrackCapture)
            }
        }

        private fun loadTrack(
            requestKey: String,
            track: KuwoTrackMetadata,
            directRid: Long?,
        ) {
            val rid = directRid ?: runCatching { KuwoApiClient.search(track) }
                .onFailure { error -> Log.w(TAG, "酷我歌曲 rid 搜索失败: title=${track.title}", error) }
                .getOrNull()
            if (rid == null) {
                Log.w(TAG, "酷我歌曲 rid 无法解析: title=${track.title}, artist=${track.artist}")
                return
            }
            if (!isCurrent(requestKey)) return

            loadCached(rid)?.let { cached ->
                if (isCurrent(requestKey)) publish(toSong(track, rid, cached))
            }

            runCatching { KuwoApiClient.fetchLyrics(rid) }
                .onSuccess { payload ->
                    writeCache(rid, payload)
                    if (!isCurrent(requestKey)) return@onSuccess
                    val song = toSong(track, rid, payload)
                    publish(song)
                    if (BuildConfig.DEBUG) {
                        val lines = song.lyrics.orEmpty()
                        val wordCount = lines.sumOf { it.words.orEmpty().size }
                        Log.i(
                            TAG,
                            "酷我歌词已发布: rid=$rid, source=${payload.source}, " +
                                "lines=${lines.size}, words=$wordCount",
                        )
                    }
                }
                .onFailure { error -> Log.w(TAG, "酷我歌词下载失败: rid=$rid", error) }
        }

        private fun startNextTrackCapture() {
            require(Looper.myLooper() == Looper.getMainLooper())
            val queries = KuwoNextTrackResolver.queries(application)
            nextTrackValidationKeys = queries.map { it.cacheKey }
            host.resolveDexMethods(
                application = application,
                queries = queries,
                callback = OfficialProviderDexMethodsCallback { targets ->
                    mainHandler.post { finishNextTrackSetup(targets) }
                },
            )
        }

        private fun finishNextTrackSetup(targets: List<OfficialProviderMethodTarget>) {
            mainHandler.removeCallbacks(periodicNextTrackCapture)
            nextTrackResolver = null
            nextTrackValidation.reset()
            val resolver = runCatching {
                KuwoNextTrackResolver.create(application, targets)
            }.onFailure { error ->
                Log.w(TAG, "酷我下一首解析器校验失败", error)
                reportNextTrackValidation(
                    valid = false,
                    detail = "resolver_validation:${error::class.java.simpleName}: ${error.message}",
                )
            }.getOrNull() ?: return
            nextTrackResolver = resolver
            mainHandler.post(periodicNextTrackCapture)
            Log.i(TAG, "酷我下一首 Hook 已安装: process=${Application.getProcessName()}")
        }

        private fun requestNextTrackCapture() {
            if (nextTrackResolver == null) return
            mainHandler.removeCallbacks(requestedNextTrackCapture)
            mainHandler.post(requestedNextTrackCapture)
        }

        private fun captureNextTrack() {
            require(Looper.myLooper() == Looper.getMainLooper())
            val metadata = currentTrack
            val resolver = nextTrackResolver
            if (metadata == null || resolver == null) {
                publishNextTrack(metadata, null)
                return
            }
            runCatching { resolver.resolve(metadata) }
                .onSuccess { rawSnapshot ->
                    val snapshot = KuwoNextTrackBinding.align(metadata, rawSnapshot)
                    when {
                        rawSnapshot == null || snapshot != null -> {
                            nextTrackValidation.record(alignmentFailed = false)
                            reportNextTrackValidation(
                                valid = true,
                                detail = "next=${snapshot?.next?.id ?: "none"}",
                            )
                        }
                        nextTrackValidation.record(alignmentFailed = true) -> {
                            reportNextTrackValidation(
                                valid = false,
                                detail = "queue_current_mismatch:${rawSnapshot.current.id}",
                            )
                            deactivateNextTrackResolver()
                        }
                    }
                    if (
                        BuildConfig.DEBUG &&
                        snapshot != null &&
                        firstNextTrackHit.compareAndSet(false, true)
                    ) {
                        Log.i(
                            TAG,
                            "酷我下一首 Hook 首次命中: current=${snapshot.current.id}, " +
                                "next=${snapshot.next?.id}",
                        )
                    }
                    publishNextTrack(metadata, snapshot)
                }
                .onFailure { error ->
                    reportNextTrackValidation(
                        valid = false,
                        detail = "${error::class.java.simpleName}: ${error.message}",
                    )
                    deactivateNextTrackResolver()
                    if (BuildConfig.DEBUG) Log.w(TAG, "酷我下一首采集失败", error)
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
            mainHandler.removeCallbacks(requestedNextTrackCapture)
        }

        private fun publishNextTrack(
            metadata: KuwoTrackMetadata?,
            snapshot: KuwoQueueSnapshot?,
        ) {
            val current = snapshot?.current
            val currentId = current?.id?.takeIf(String::isNotBlank)
                ?: KuwoTrackIdResolver.directRid(metadata?.mediaId)?.toString()
                ?: metadata?.mediaId.orEmpty()
            val currentTitle = current?.title?.takeIf(String::isNotBlank)
                ?: metadata?.title.orEmpty()
            val currentArtist = current?.artist?.takeIf(String::isNotBlank)
                ?: metadata?.artist.orEmpty()
            val next = snapshot?.next
            val frame = when {
                metadata == null -> OfficialProviderControlProtocol.encodeNextTrackClear()
                next == null || next.title.isBlank() ->
                    OfficialProviderControlProtocol.encodeNextTrackClear(
                        currentId = currentId,
                        currentTitle = currentTitle,
                        currentArtist = currentArtist,
                    )
                else -> OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = currentId,
                    currentTitle = currentTitle,
                    currentArtist = currentArtist,
                    nextId = next.id,
                    nextTitle = next.title,
                    nextArtist = next.artist,
                    nextAlbum = next.album,
                    nextDurationMs = next.durationMs,
                )
            }
            val now = SystemClock.elapsedRealtime()
            if (
                frame == lastNextTrackFrame &&
                now - lastNextTrackFrameSentAtMs < NEXT_TRACK_HEARTBEAT_MS
            ) {
                return
            }
            if (provider?.player?.sendText(frame) == true) {
                lastNextTrackFrame = frame
                lastNextTrackFrameSentAtMs = now
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "酷我下一首控制帧已发送: current=$currentId, next=${next?.id}",
                    )
                }
            }
        }

        private fun isCurrent(requestKey: String): Boolean = requestGuard.isCurrent(requestKey)

        private fun publish(song: Song) {
            if (lastSong == song) return
            lastSong = song
            provider?.player?.setSong(song)
        }

        private fun placeholder(
            track: KuwoTrackMetadata,
            directRid: Long?,
            requestKey: String,
        ): Song = Song().apply {
            id = directRid?.toString() ?: track.mediaId?.takeIf(String::isNotBlank) ?: requestKey
            name = track.title
            artist = track.artist
            duration = track.durationMs.coerceAtLeast(0L)
        }

        private fun loadCached(rid: Long): KuwoLyricsPayload? {
            val file = File(cacheDir, "$rid.json")
            if (!file.isFile) return null
            return runCatching {
                KuwoLyricsPayload.fromJson(JSONObject(file.readText()))
            }.onFailure { error ->
                if (BuildConfig.DEBUG) Log.w(TAG, "酷我歌词缓存读取失败: rid=$rid", error)
            }.getOrNull()
        }

        private fun writeCache(rid: Long, payload: KuwoLyricsPayload) {
            runCatching {
                File(cacheDir, "$rid.json").writeText(payload.toJson().toString())
            }.onFailure { error ->
                if (BuildConfig.DEBUG) Log.w(TAG, "酷我歌词缓存写入失败: rid=$rid", error)
            }
        }
    }

    private const val NEXT_TRACK_POLL_INTERVAL_MS = 1_500L
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L

    private fun toSong(
        track: KuwoTrackMetadata,
        rid: Long,
        payload: KuwoLyricsPayload,
    ): Song {
        val timeline = KuwoLyricsParser.parse(payload.raw)
        val lyrics = timeline.map { line ->
            RichLyricLine().apply {
                begin = line.begin
                end = line.end
                duration = (line.end - line.begin).coerceAtLeast(0L)
                text = line.text
                translation = line.translation
                roma = line.roma
                words = line.words.map { word ->
                    LyricWord().apply {
                        begin = word.begin
                        end = word.end
                        duration = (word.end - word.begin).coerceAtLeast(0L)
                        text = word.text
                    }
                }.takeIf(List<LyricWord>::isNotEmpty)
            }
        }
        return Song().apply {
            id = rid.toString()
            name = track.title
            artist = track.artist
            duration = track.durationMs.takeIf { it > 0L } ?: lyrics.lastOrNull()?.end ?: 0L
            this.lyrics = lyrics.takeIf(List<RichLyricLine>::isNotEmpty)
        }
    }
}

internal class KuwoNextTrackValidationTracker(
    private val invalidThreshold: Int = 3,
) {
    private var consecutiveAlignmentFailures = 0

    init {
        require(invalidThreshold > 0)
    }

    @Synchronized
    fun record(alignmentFailed: Boolean): Boolean {
        if (!alignmentFailed) {
            consecutiveAlignmentFailures = 0
            return false
        }
        consecutiveAlignmentFailures += 1
        if (consecutiveAlignmentFailures < invalidThreshold) return false
        consecutiveAlignmentFailures = 0
        return true
    }

    @Synchronized
    fun reset() {
        consecutiveAlignmentFailures = 0
    }
}
