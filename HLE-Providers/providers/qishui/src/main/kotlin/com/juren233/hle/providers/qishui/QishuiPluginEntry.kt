/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.genius.hyperlyrics.provider.OfficialProviderSystemMediaCallback
import com.genius.hyperlyrics.provider.OfficialProviderSystemMediaHost
import com.genius.hyperlyrics.provider.OfficialProviderSystemMediaPlugin
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs entirely in SystemUI. Playback identity comes from the public MediaSession;
 * lyric content is fetched from Qishui's PC track API.
 */
object QishuiPluginEntry : OfficialProviderSystemMediaPlugin {
    private const val TAG = "HLEProvider/Qishui"
    private const val TARGET_PACKAGE = "com.luna.music"
    private const val PROVIDER_PACKAGE =
        "com.genius.hyperlyrics.provider.qishui"

    @Volatile
    private var runtime: QishuiRuntime? = null

    override fun installSystemMedia(host: OfficialProviderSystemMediaHost) {
        require(host.playerPackageName == TARGET_PACKAGE) {
            "Unexpected target package: ${host.playerPackageName}"
        }
        val newRuntime = QishuiRuntime(host)
        newRuntime.start()
        runtime = newRuntime
    }

    override fun releaseSystemMedia() {
        runtime?.stop()
        runtime = null
    }

    private class QishuiRuntime(
        private val host: OfficialProviderSystemMediaHost,
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-Qishui-Lyrics").apply { isDaemon = true }
        }
        private val lyricCache = Collections.synchronizedMap(
            object : LinkedHashMap<String, QishuiLyricPayload>(32, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, QishuiLyricPayload>?,
                ): Boolean = size > MAX_LYRIC_CACHE_SIZE
            },
        )

        @Volatile
        private var currentRequestKey: String? = null

        private var retryRunnable: Runnable? = null
        private var retryAttempt = 0

        private var lastPublishedSongKey: String? = null

        @Volatile
        private var provider: LyriconProvider? = null

        private var subscription:
            com.genius.hyperlyrics.provider.OfficialProviderSystemMediaSubscription? = null

        fun start() {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "汽水 SystemMedia Provider 必须在主线程启动"
            }
            provider = LyriconFactory.createProvider(
                context = host.application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = host.playerPackageName,
            ).also {
                it.player.setDisplayTranslation(true)
                it.player.setDisplayRoma(false)
                it.register()
            }
            subscription = host.subscribe(
                OfficialProviderSystemMediaCallback { metadata, state ->
                    onMediaChanged(metadata, state)
                },
            )
            Log.i(
                TAG,
                "汽水音乐 SystemMedia Provider 已注册: host=${host.application.packageName}",
            )
        }

        fun stop() {
            cancelRetry()
            subscription?.release()
            subscription = null
            executor.shutdownNow()
            provider?.unregister()
            provider?.destroy()
            provider = null
            currentRequestKey = null
            lyricCache.clear()
        }

        private fun onMediaChanged(
            metadata: MediaMetadata?,
            state: PlaybackState?,
        ) {
            provider?.player?.setPlaybackState(state)
            if (metadata == null) {
                cancelRetry()
                currentRequestKey = null
                lastPublishedSongKey = null
                return
            }
            val track = QishuiTrackMetadata(
                mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            if (track.mediaId.isNullOrBlank() && track.title.isNullOrBlank()) return
            val trackId = QishuiTrackIdentity.apiTrackId(track.mediaId)
            val requestKey = trackId ?: buildString {
                append("metadata:")
                append(track.title.orEmpty())
                append('|')
                append(track.artist.orEmpty())
            }
            if (currentRequestKey == requestKey) return
            cancelRetry()
            retryAttempt = 0
            currentRequestKey = requestKey
            publishPlaceholder(track, trackId ?: requestKey)

            if (trackId == null) {
                Log.w(TAG, "系统 MediaSession 未提供可用的汽水 track_id: mediaId=${track.mediaId}")
                return
            }
            lyricCache[trackId]?.let { cached ->
                publishLyrics(track, cached)
                return
            }
            requestLyrics(track, trackId)
        }

        private fun requestLyrics(track: QishuiTrackMetadata, trackId: String) {
            executor.execute {
                runCatching { QishuiApiClient.fetch(trackId) }
                    .onSuccess { payload ->
                        lyricCache[trackId] = payload
                        mainHandler.post {
                            if (currentRequestKey == trackId) {
                                cancelRetry()
                                retryAttempt = 0
                                publishLyrics(track, payload)
                            }
                        }
                    }
                    .onFailure { error ->
                        Log.w(TAG, "汽水歌词网络请求失败: trackId=$trackId", error)
                        mainHandler.post {
                            if (currentRequestKey == trackId) {
                                scheduleRetry(track, trackId)
                            }
                        }
                    }
            }
        }

        private fun scheduleRetry(track: QishuiTrackMetadata, trackId: String) {
            cancelRetry()
            val delayMs = QishuiRetryPolicy.delayMs(retryAttempt++)
            retryRunnable = Runnable {
                retryRunnable = null
                if (currentRequestKey == trackId && lyricCache[trackId] == null) {
                    requestLyrics(track, trackId)
                }
            }.also { mainHandler.postDelayed(it, delayMs) }
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "汽水歌词将在 ${delayMs}ms 后自动重试: trackId=$trackId, " +
                        "attempt=$retryAttempt",
                )
            }
        }

        private fun cancelRetry() {
            retryRunnable?.let(mainHandler::removeCallbacks)
            retryRunnable = null
        }

        private fun publishPlaceholder(track: QishuiTrackMetadata, id: String) {
            val key = "placeholder:$id:${track.title}:${track.artist}:${track.durationMs}"
            if (key == lastPublishedSongKey) return
            provider?.player?.setSong(Song().apply {
                this.id = id
                name = track.title
                artist = track.artist
                duration = track.durationMs.coerceAtLeast(0L)
            })
            lastPublishedSongKey = key
        }

        private fun publishLyrics(
            track: QishuiTrackMetadata,
            payload: QishuiLyricPayload,
        ) {
            check(Looper.myLooper() == Looper.getMainLooper())
            val lines = QishuiLyricsParser.parse(payload, track.durationMs)
            if (lines.isEmpty()) return
            val key = "lyrics:${payload.trackId}:${payload.content.hashCode()}:" +
                "${payload.translations.hashCode()}:${payload.timeline.hashCode()}"
            if (key == lastPublishedSongKey) return
            provider?.player?.setSong(Song().apply {
                id = payload.trackId
                name = track.title
                artist = track.artist
                duration = track.durationMs.takeIf { it > 0L } ?: lines.last().end
                lyrics = lines.map { line ->
                    RichLyricLine().apply {
                        begin = line.begin
                        end = line.end
                        duration = (line.end - line.begin).coerceAtLeast(0L)
                        text = line.text
                        translation = line.translation
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
            })
            lastPublishedSongKey = key
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "汽水网络歌词已发布: trackId=${payload.trackId}, lines=${lines.size}, " +
                        "source=${payload.source}, " +
                        "translations=${payload.translations.map { it.language }}",
                )
            }
        }
    }

    private const val MAX_LYRIC_CACHE_SIZE = 32
}

internal object QishuiRetryPolicy {
    private val delaysMs = longArrayOf(
        15_000L,
        30_000L,
        60_000L,
        120_000L,
        300_000L,
    )

    fun delayMs(failureCount: Int): Long = delaysMs[
        failureCount.coerceAtLeast(0).coerceAtMost(delaysMs.lastIndex)
    ]
}

internal data class QishuiTrackMetadata(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)
