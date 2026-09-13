/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicNextTrackProfilesTest {
    @Test
    fun `uses exact original DEX identifiers for QQ Music 20 6 5 8`() {
        val profile = QQMusicNextTrackProfiles.resolve(
            QQMusicRuntimePlan.MOBILE_PACKAGE,
            "20.6.5.8",
            7228L,
        )!!

        assertEquals("com.tencent.qqmusic", profile.packageName)
        assertEquals("com.tencent.qqmusic.common.player.d", profile.managerClassName)
        assertEquals("z", profile.singletonMethodName)
        assertEquals("M", profile.currentSongMethodName)
        assertEquals("E", profile.nextSongMethodName)
        assertEquals("com.tencent.qqmusicplayerprocess.songinfo.SongInfo", profile.songInfoClassName)
        assertEquals("D2", profile.songIdMethodName)
        assertEquals("f3", profile.songTitleMethodName)
        assertEquals("R3", profile.songArtistMethodName)
    }

    @Test
    fun `uses exact original DEX identifiers for QQ Music 20 7 5 8`() {
        val profile = QQMusicNextTrackProfiles.resolve(
            QQMusicRuntimePlan.MOBILE_PACKAGE,
            "20.7.5.8",
            7308L,
        )!!

        assertEquals("com.tencent.qqmusic", profile.packageName)
        assertEquals("com.tencent.qqmusic.common.player.d", profile.managerClassName)
        assertEquals("B", profile.singletonMethodName)
        assertEquals("O", profile.currentSongMethodName)
        assertEquals("G", profile.nextSongMethodName)
        assertEquals("com.tencent.qqmusicplayerprocess.songinfo.SongInfo", profile.songInfoClassName)
        assertEquals("I2", profile.songIdMethodName)
        assertEquals("j3", profile.songTitleMethodName)
        assertEquals("W3", profile.songArtistMethodName)
    }

    @Test
    fun `uses exact original DEX identifiers for QQ Music HD 6 12 0 5`() {
        val profile = QQMusicNextTrackProfiles.resolve(
            QQMusicRuntimePlan.HD_PACKAGE,
            "6.12.0.5",
            6_120_005L,
        )!!

        assertEquals("com.tencent.qqmusicpad", profile.packageName)
        assertEquals(
            "com.tencent.qqmusic.qplayer.core.player.MusicPlayerHelper",
            profile.managerClassName,
        )
        assertEquals("a0", profile.singletonMethodName)
        assertEquals("l0", profile.currentSongMethodName)
        assertEquals("g0", profile.nextSongMethodName)
        assertEquals("com.tencent.qqmusic.openapisdk.model.SongInfo", profile.songInfoClassName)
        assertEquals("getSongId", profile.songIdMethodName)
        assertEquals("getSongName", profile.songTitleMethodName)
        assertEquals("getSingerName", profile.songArtistMethodName)
    }

    @Test
    fun `uses exact original DEX identifiers for Xiaomi Music 4 44 0 9`() {
        val profile = QQMusicNextTrackProfiles.resolve(
            QQMusicRuntimePlan.MIUI_PACKAGE,
            "4.44.0.9",
            4_440_009L,
        )!!

        assertEquals("com.miui.player", profile.packageName)
        assertEquals(
            "com.tencent.qqmusicsdk.protocol.MusicPlayer",
            profile.managerClassName,
        )
        assertEquals("getInstance", profile.singletonMethodName)
        assertEquals("getCurSong", profile.currentSongMethodName)
        assertEquals("getNextSong", profile.nextSongMethodName)
        assertEquals(
            "com.tencent.qqmusicsdk.protocol.SongInfomation",
            profile.songInfoClassName,
        )
        assertEquals("getId", profile.songIdMethodName)
        assertEquals("getName", profile.songTitleMethodName)
        assertEquals("getSingerName", profile.songArtistMethodName)
    }

    @Test
    fun `builds package specific query keys and HD caller semantics`() {
        val mobile = QQMusicNextTrackResolver.queries(
            QQMusicRuntimePlan.MOBILE_PACKAGE,
            "20.6.5.8",
            7228L,
        )!!
        val hd = QQMusicNextTrackResolver.queries(
            QQMusicRuntimePlan.HD_PACKAGE,
            "6.12.0.5",
            6_120_005L,
        )!!

        assertTrue(mobile.all { it.cacheKey.startsWith("qqmusic-mobile-") })
        assertTrue(hd.all { it.cacheKey.startsWith("qqmusic-hd-") })
        assertTrue(mobile.all { it.cacheKey.endsWith("-v4") })
        assertTrue(hd.all { it.cacheKey.endsWith("-v4") })
        assertEquals(listOf("getCurrentSongInfo"), hd[1].requiredCallerMethodNames)
        assertEquals(listOf("getNextSongInfo"), hd[2].requiredCallerMethodNames)
        assertEquals(
            listOf("Lcom/tencent/qqmusic/openapisdk/model/SongInfo;->getSongId()J"),
            hd[1].forbiddenInvokedMethodDescriptors,
        )
        assertNull(hd[1].preferredTarget)
        assertNull(hd[2].preferredTarget)
        assertTrue(hd.none {
            it.preferredTarget != null && it.requiredCallerMethodNames.isNotEmpty()
        })
    }

    @Test
    fun `unknown versions keep the nearest verified template for the same package family`() {
        val mobile = QQMusicNextTrackProfiles.resolve(
            QQMusicRuntimePlan.MOBILE_PACKAGE,
            "20.6.5.9",
            7229L,
        )
        val hd = QQMusicNextTrackResolver.queries(
            QQMusicRuntimePlan.HD_PACKAGE,
            "6.13.0.0",
            6_130_000L,
        )
        val miui = QQMusicNextTrackResolver.queries(
            QQMusicRuntimePlan.MIUI_PACKAGE,
            "4.45.0.0",
            4_450_000L,
        )

        assertNotNull(mobile)
        assertEquals(QQMusicRuntimePlan.MOBILE_PACKAGE, mobile?.packageName)
        assertNotNull(hd)
        assertTrue(hd!!.all { it.cacheKey.startsWith("qqmusic-hd-") })
        assertEquals(
            listOf("Lcom/tencent/qqmusic/openapisdk/model/SongInfo;->getSongId()J"),
            hd[1].forbiddenInvokedMethodDescriptors,
        )
        assertNotNull(miui)
        assertTrue(miui!!.all { it.cacheKey.startsWith("qqmusic-miui-") })
        assertNull(miui[0].declaringClassNamePrefix)
        assertEquals(
            "com.tencent.qqmusicsdk.protocol.MusicPlayer",
            miui[0].declaringClassName,
        )
        assertTrue(miui[1].requiredInvokedMethodNames.isEmpty())
        assertTrue(miui[2].requiredInvokedMethodNames.isEmpty())
        assertNotNull(miui[1].preferredTarget)
        assertNotNull(miui[2].preferredTarget)
    }

    @Test
    fun `does not reuse a template across package families`() {
        val mobile = QQMusicNextTrackResolver.queries(
            QQMusicRuntimePlan.MOBILE_PACKAGE,
            "6.12.0.5",
            6_120_005L,
        )
        assertNotNull(mobile)
        assertTrue(mobile!!.all { it.cacheKey.startsWith("qqmusic-mobile-") })
        assertTrue(mobile.none { it.cacheKey.startsWith("qqmusic-hd-") })
        assertNull(
            QQMusicNextTrackResolver.queries(
                "com.example.music",
                "6.12.0.5",
                6_120_005L,
            )
        )
    }

    @Test
    fun `parses song metadata from shortMessage fallback format`() {
        val sample = "1 id = 509076686 name = Die With A Smile singer = Lady Gaga/Bruno Mars tmpPlayKey = O4M0003FFvMu0tMeku.mgg enableTuningEffect = false tuningEffectJson = "
        val snapshot = QQMusicNextTrackResolver.parseShortMessageText(sample)
        assertNotNull(snapshot)
        assertEquals("509076686", snapshot?.id)
        assertEquals("Die With A Smile", snapshot?.title)
        assertEquals("Lady Gaga/Bruno Mars", snapshot?.artist)
    }
}
