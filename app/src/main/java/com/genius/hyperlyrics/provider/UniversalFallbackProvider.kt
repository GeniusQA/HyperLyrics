/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.provider

import android.app.Application
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.genius.hyperlyrics.common.lyric.LyricMetadataKeys
import com.genius.hyperlyrics.lyric.model.Song
import com.genius.hyperlyrics.lyric.model.lyricMetadataOf
import com.genius.hyperlyrics.provider.ytmusic.YoutubeMusicProviderConstants
import com.genius.hyperlyrics.root.utils.HookLogger
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.ProviderMetadata
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 通用兜底 Provider 的包名。独立子包，避免与 Salt 控制型 Provider（com.genius.hyperlyrics）
 * 或 Apple 直连桥（com.genius.hyperlyrics.apple）在 Central 注册时冲突。
 */
private const val FALLBACK_PROVIDER_PACKAGE = "com.genius.hyperlyrics.universal"

/**
 * 通用兜底 Provider：把“无任何专用 Provider 的任意播放器”也注册成 Lyricon Provider，
 * 经 player.setSong/setPlaybackState 推给 Central，与 YouTube Music 等内置 Provider 走
 * 完全相同的发布链路（由 LyriconSource 的订阅回调 onActiveProviderChanged /
 * onPlaybackStateChanged 稳定驱动 currentLyricPackageName 与 currentPlaybackState），
 * 从而消除此前手写桥 maybeFeedLocalFallbackSong 在 AOD/锁屏/通知上的 pause_policy、
 * packageMatches 竞态。歌词/翻译抓取仍复用 LyriconSource 既有的在线兜底链路。
 *
 * 与 OfficialProviderSystemMediaHostImpl 的区别：它只服务一个固定 playerPackageName，
 * 而此处要动态服务“任意未装专用 Provider 的播放器”，所以改为在切换活跃播放器时按
 * playerPackageName 重新创建 Provider 实例。
 */
internal class UniversalFallbackProvider(
    private val application: Application,
    private val isFallbackAllowedFor: (String) -> Boolean,
    private val onDiagnostic: (String, String) -> Unit = { _, _ -> },
) {
    private val tag = "UniversalFallback"
    private val handler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val manager = application.getSystemService(Application.MEDIA_SESSION_SERVICE)
        as MediaSessionManager

    @Volatile
    private var provider: LyriconProvider? = null
    @Volatile
    private var providerPackage: String? = null
    @Volatile
    private var trackedController: MediaController? = null
    @Volatile
    private var trackedCallback: MediaController.Callback? = null
    @Volatile
    private var currentTrackKey: String? = null

    fun start() {
        HookLogger.i(tag, "通用兜底 Provider 已初始化")
    }

    fun release() {
        unregisterTrackedController()
        runCatching { provider?.player?.setSong(null) }
        provider = null
        providerPackage = null
        currentTrackKey = null
        HookLogger.i(tag, "通用兜底 Provider 已释放")
    }

    /** 由 LyriconSource 在本地媒体会话变化时调用。 */
    fun onSessionsChanged(controllers: List<MediaController>?) {
        updateControllers(controllers.orEmpty())
    }

    /** 由 LyriconSource 在专属 Provider 接管/切换后调用，重新评估是否仍需兜底。 */
    fun reconsider() {
        updateControllers(runCatching { manager.getActiveSessions(null) }.getOrDefault(emptyList()))
    }

    private fun updateControllers(controllers: List<MediaController>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { updateControllers(controllers) }
            return
        }
        val selected = selectFallbackController(controllers)
        if (selected == null) {
            if (currentTrackKey != null) {
                currentTrackKey = null
                runCatching { provider?.player?.setSong(null) }
            }
            unregisterTrackedController()
            return
        }
        if (selected.sessionToken != trackedController?.sessionToken) {
            unregisterTrackedController()
            trackedController = selected
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publishCurrent()
                override fun onPlaybackStateChanged(state: PlaybackState?) =
                    publishPlaybackState(state)
            }
            trackedCallback = callback
            runCatching { selected.registerCallback(callback, handler) }
                .onFailure { Log.w(tag, "兜底 MediaController 回调注册失败", it) }
        }
        publishCurrent()
        publishPlaybackState(selected.playbackState)
    }

    private fun selectFallbackController(controllers: List<MediaController>): MediaController? {
        var best: MediaController? = null
        var bestScore = -1
        for (controller in controllers) {
            val pkg = controller.packageName ?: continue
            if (!isFallbackAllowedFor(pkg)) continue
            val state = controller.playbackState?.state
            val playing = state == PlaybackState.STATE_PLAYING
            val hasTitle = !controller.metadata
                ?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
            val score = when {
                playing && hasTitle -> 2
                playing -> 1
                hasTitle -> 0
                else -> -1
            }
            if (score > bestScore) {
                bestScore = score
                best = controller
            }
        }
        return best
    }

    private fun publishCurrent() {
        val controller = trackedController ?: return
        val metadata = controller.metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (title.isEmpty()) return
        val artist = (
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ).orEmpty().trim()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val album = (
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
                ?: metadata.description?.description?.toString()
            ).orEmpty().trim()
        val pkg = controller.packageName ?: return
        val key = listOf(mediaId.orEmpty(), title, artist, duration).joinToString("|")
        if (key == currentTrackKey) return
        currentTrackKey = key
        val baseSong = Song(
            id = mediaId?.takeIf { it.isNotBlank() } ?: key,
            name = title,
            artist = artist,
            duration = duration,
            // 专辑名写入元数据，供后续在线搜索优先按「歌名 歌手 专辑」请求。
            metadata = album.takeIf { it.isNotBlank() }
                ?.let { lyricMetadataOf(LyricMetadataKeys.MEDIA_ALBUM to it) },
        )
        val p = ensureProvider(pkg) ?: return
        runCatching {
            p.player.setSong(toRemoteSong(baseSong))
            onDiagnostic(
                tag,
                "兜底发布基础歌曲: pkg=$pkg, title=$title, artist=$artist, " +
                    "album=${album.ifBlank { "无" }}",
            )
        }.onFailure {
            HookLogger.e(tag, "兜底 setSong 失败: title=$title", it)
        }
    }

    private fun publishPlaybackState(state: PlaybackState?) {
        runCatching { provider?.player?.setPlaybackState(state) }
    }

    private fun ensureProvider(pkg: String): LyriconProvider? {
        if (provider != null && providerPackage == pkg) return provider
        // 切换播放器：清空旧 Provider 的歌（避免继续发布旧包），注册新包名的兜底 Provider。
        runCatching { provider?.player?.setSong(null) }
        provider = null
        providerPackage = null
        val created = runCatching {
            LyriconFactory.createProvider(
                context = application,
                providerPackageName = FALLBACK_PROVIDER_PACKAGE,
                playerPackageName = pkg,
                logo = ProviderLogo.fromBase64(YoutubeMusicProviderConstants.ICON),
                metadata = ProviderMetadata(mapOf("hyperlyrics_fallback" to "true")),
            ).also { it.register() }
        }.onFailure {
            HookLogger.e(tag, "兜底 Provider 注册失败: pkg=$pkg", it)
        }.getOrNull()
        provider = created
        providerPackage = pkg
        return created
    }

    private fun unregisterTrackedController() {
        val controller = trackedController
        val callback = trackedCallback
        if (controller != null && callback != null) {
            runCatching { controller.unregisterCallback(callback) }
        }
        trackedController = null
        trackedCallback = null
    }

    private fun toRemoteSong(song: Song): LyriconSong {
        val encoded = json.encodeToString(song)
        return json.decodeFromString(encoded)
    }
}
