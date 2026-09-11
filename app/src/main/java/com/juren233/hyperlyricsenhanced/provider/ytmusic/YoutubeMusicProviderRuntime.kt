/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider.ytmusic

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.root.source.OnlineFallbackSongMapper
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Runtime half of the built-in YouTube Music provider.
 *
 * It mirrors what the standalone Lyricon module did: observe the player's public MediaSession,
 * publish the active track to Lyricon Central, and fill lyrics from an online source. The
 * difference is that everything runs inside HyperLyrics, so the player only needs this
 * module in scope - no separate APK and no Provider Pack is involved.
 */
internal class YoutubeMusicProviderRuntime(
    private val host: OfficialProviderHost,
) {
    private val tag = YoutubeMusicProviderConstants.TAG
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Volatile
    private var player: RemotePlayer? = null

    @Volatile
    private var application: Application? = null

    @Volatile
    private var currentTrackKey: String? = null

    @Volatile
    private var latestSong: Song? = null

    private var fetchJob: Job? = null

    fun install() {
        host.hookApplication { app -> attach(app) }
        host.hookMediaSession(
            playbackStateCallback = { state -> onPlaybackStateChanged(state) },
            metadataCallback = { metadata -> onMetadataChanged(metadata) },
        )
    }

    fun release() {
        fetchJob?.cancel()
        fetchJob = null
        scope.cancel()
        player?.setSong(null)
        player = null
    }

    private fun attach(app: Application) {
        application = app
        val helper = runCatching {
            LyriconFactory.createProvider(
                context = app,
                providerPackageName = YoutubeMusicProviderConstants.PROVIDER_PACKAGE_NAME,
                playerPackageName = host.packageName,
                logo = ProviderLogo.fromBase64(YoutubeMusicProviderConstants.ICON),
            ).also { it.register() }
        }.onFailure { error ->
            Log.e(tag, "Lyricon 提供器注册失败", error)
        }.getOrNull()
        player = helper?.player
        player?.setPositionUpdateInterval(POSITION_UPDATE_INTERVAL_MS)
        latestSong?.let { song -> player?.setSong(toRemoteSong(song)) }
    }

    private fun onMetadataChanged(metadata: MediaMetadata?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            )?.trim().orEmpty()
        if (title.isEmpty() && artist.isEmpty()) {
            currentTrackKey = null
            latestSong = null
            fetchJob?.cancel()
            fetchJob = null
            player?.setSong(null)
            return
        }

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val key = listOf(mediaId.orEmpty(), title, artist, duration).joinToString("|")
        if (key == currentTrackKey) return
        currentTrackKey = key

        val baseSong = Song(
            id = mediaId?.takeIf { it.isNotBlank() } ?: key,
            name = title,
            artist = artist,
            duration = duration,
        )
        // 同一首歌的元数据微变（歌手补齐、时长毫秒漂移）会改变 key；
        // 若此前已抓到歌词，必须继续发布带歌词的版本，绝不能被裸歌曲降级覆盖。
        val previous = latestSong
        if (previous != null && !previous.lyrics.isNullOrEmpty() && isSameTrackIdentity(previous, baseSong)) {
            player?.setSong(toRemoteSong(previous))
            return
        }

        fetchJob?.cancel()
        latestSong = baseSong
        // Publish the track immediately so Central sees the player, then enrich it with lyrics.
        player?.setSong(toRemoteSong(baseSong))
        fetchJob = scope.launch { fetchLyrics(key, baseSong) }
    }

    private fun isSameTrackIdentity(a: Song, b: Song): Boolean =
        a.name.orEmpty().equals(b.name.orEmpty(), ignoreCase = true) &&
            a.artist.orEmpty().equals(b.artist.orEmpty(), ignoreCase = true)

    private suspend fun fetchLyrics(trackKey: String, baseSong: Song) {
        val onlineEnabled = host.getBooleanPreference(
            RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_YOUTUBE_MUSIC,
            RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_YOUTUBE_MUSIC,
        )
        if (!onlineEnabled) {
            Log.i(tag, "YouTube Music 在线源补全已关闭，跳过歌词抓取")
            return
        }
        val app = application ?: return
        // 每次抓词前按远端偏好解析来源顺序，保证与设置页的来源开关/排序实时一致，
        // 避免 Provider 进程因默认回退（QQ/网易）无视用户关闭的来源。
        val sourceOrder = OnlineTranslationSourcePreferences.orderedSources(
            rawOrder = host.getStringPreference(
                RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
                RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
            ),
            automaticSelection = host.getBooleanPreference(
                RootConstants.KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
                RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
            ),
            isEnabled = { source ->
                host.getBooleanPreference(
                    OnlineTranslationSourcePreferences.sourcePreferenceKey(source),
                    OnlineTranslationSourcePreferences.sourceDefaultEnabled(source),
                )
            },
        )
        host.reportDiagnostic(
            tag,
            "抓词开始: title=${baseSong.name}, artist=${baseSong.artist}, " +
                "时长=${baseSong.duration}ms, 来源=${sourceOrder.joinToString { it.name }}",
        )
        val lines = runCatching {
            OnlineLyricTargeter.fetchBestLyric(
                context = app,
                pkgName = host.packageName,
                title = baseSong.name.orEmpty(),
                artist = baseSong.artist.orEmpty(),
                durationMs = baseSong.duration,
                sourceOrder = sourceOrder,
            )
        }.onFailure { error ->
            Log.w(tag, "在线歌词获取失败: title=${baseSong.name}", error)
            host.reportDiagnostic(tag, "抓词失败: title=${baseSong.name}, ${error.message}")
        }.getOrNull()
        host.reportDiagnostic(
            tag,
            "抓词结束: title=${baseSong.name}, 行数=${lines?.size ?: 0}",
        )

        if (lines.isNullOrEmpty()) return
        if (currentTrackKey != trackKey) return
        val withLyrics = OnlineFallbackSongMapper.map(baseSong, lines) ?: return
        if (currentTrackKey != trackKey) return
        latestSong = withLyrics
        player?.setSong(toRemoteSong(withLyrics))
    }

    private fun onPlaybackStateChanged(state: PlaybackState?) {
        val remote = player ?: return
        remote.setPlaybackState(state)
        val position = state?.position ?: return
        if (position >= 0L) remote.setPosition(position)
    }

    private fun toRemoteSong(song: Song): LyriconSong {
        val encoded = json.encodeToString(song)
        return json.decodeFromString(encoded)
    }

    private companion object {
        const val POSITION_UPDATE_INTERVAL_MS = 200
    }
}
