/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

internal data class SaltPlayerQueueHookProfile(
    val circleModeName: String,
    val circleEndModeName: String,
    val repeatOneModeName: String,
    val randomModeName: String,
)

internal data class SaltPlayerSongHookProfile(
    val className: String,
    val idGetterName: String,
    val titleGetterName: String,
    val artistGetterName: String,
    val albumGetterName: String,
    val durationGetterName: String,
)

internal data class SaltPlayerHookProfile(
    val versionName: String,
    val versionCode: Long,
    val musicControllerClassName: String,
    val stateFlowClassName: String,
    val queue: SaltPlayerQueueHookProfile,
    val song: SaltPlayerSongHookProfile,
)

/**
 * Only stable runtime contracts remain in this registry.
 *
 * Queue state, queue item and playback-mode classes are obtained from the live
 * StateFlow value. Their obfuscated class and field names are deliberately not
 * persisted because the structural order is stable while those names are not.
 */
internal object SaltPlayerHookProfiles {
    private const val MUSIC_CONTROLLER_CLASS = "com.salt.music.service.MusicController"
    private const val STATE_FLOW_CLASS = "kotlinx.coroutines.flow.StateFlow"
    private const val SONG_CLASS = "com.salt.music.data.entry.Song"

    private val QUEUE = SaltPlayerQueueHookProfile(
        circleModeName = "Circle",
        circleEndModeName = "CircleEnd",
        repeatOneModeName = "RepeatOne",
        randomModeName = "Random",
    )

    private val SONG = SaltPlayerSongHookProfile(
        className = SONG_CLASS,
        idGetterName = "getId",
        titleGetterName = "getTitle",
        artistGetterName = "getArtist",
        albumGetterName = "getAlbum",
        durationGetterName = "getDuration",
    )

    private fun profile(versionName: String, versionCode: Long) = SaltPlayerHookProfile(
        versionName = versionName,
        versionCode = versionCode,
        musicControllerClassName = MUSIC_CONTROLLER_CLASS,
        stateFlowClassName = STATE_FLOW_CLASS,
        queue = QUEUE,
        song = SONG,
    )

    val V12_1_1 = profile("12.1.1", 2_026_070_502L)
    val V12_1_0 = profile("12.1.0", 2_026_070_208L)
    val V12_0_0 = profile("12.0.0", 2_026_061_801L)
    val V11_1_0 = profile("11.1.0", 2_026_031_101L)

    private val exactProfiles = listOf(V12_1_1, V12_1_0, V12_0_0, V11_1_0)

    val compatibilityProfile: SaltPlayerHookProfile = V12_1_1

    /**
     * Salt Player 12.2.0 introduced its native Lyricon Provider. Newer releases
     * must keep the official Pack out of the lyric/source arbitration path; the
     * Pack is still useful for the independent next-track control channel.
     */
    fun usesNativeLyricon(versionName: String): Boolean {
        val parts = versionName
            .substringBefore('-')
            .split('.')
            .mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return false
        val major = parts[0]
        val minor = parts[1]
        val patch = parts.getOrElse(2) { 0 }
        return major > 12 || (major == 12 && (minor > 2 || (minor == 2 && patch >= 0)))
    }

    fun resolve(versionName: String, versionCode: Long): SaltPlayerHookProfile =
        exactProfiles.firstOrNull {
            it.versionName == versionName && it.versionCode == versionCode
        } ?: compatibilityProfile
}
