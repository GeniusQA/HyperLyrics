/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuwoHookProfilesTest {
    @Test
    fun `uses exact original DEX identifiers for Kuwo 12 1 8 2`() {
        val profile = KuwoHookProfiles.resolve("12.1.8.2", 12_182L)

        assertEquals("cn.kuwo.mod.playcontrol.n", profile.playback.managerClassName)
        assertEquals("cn.kuwo.base.bean.IContent", profile.playback.contentClassName)
        assertEquals("cn.kuwo.base.bean.Music", profile.playback.musicClassName)
        assertEquals("L", profile.playback.singletonMethodName)
        assertEquals("S", profile.playback.currentMusicMethodName)
        assertEquals("g0", profile.playback.nextContentMethodName)
        assertEquals("rid", profile.music.ridFieldName)
        assertEquals("name", profile.music.titleFieldName)
        assertEquals("artist", profile.music.artistFieldName)
        assertEquals("album", profile.music.albumFieldName)
        assertEquals("duration", profile.music.durationSecondsFieldName)
    }

    @Test
    fun `uses exact original DEX identifiers for Kuwo 12 2 2 0`() {
        val profile = KuwoHookProfiles.resolve("12.2.2.0", 12_220L)

        assertEquals("cn.kuwo.mod.playcontrol.n", profile.playback.managerClassName)
        assertEquals("cn.kuwo.base.bean.IContent", profile.playback.contentClassName)
        assertEquals("cn.kuwo.base.bean.Music", profile.playback.musicClassName)
        // 12.2.2.0 obfuscation shift, verified against the original APK DEX:
        // the old names still exist with unrelated signatures (L()I, S()I, g0()MusicList).
        assertEquals("O", profile.playback.singletonMethodName)
        assertEquals("X", profile.playback.currentMusicMethodName)
        assertEquals("k0", profile.playback.nextContentMethodName)
        assertEquals("rid", profile.music.ridFieldName)
        assertEquals("name", profile.music.titleFieldName)
        assertEquals("artist", profile.music.artistFieldName)
        assertEquals("album", profile.music.albumFieldName)
        assertEquals("duration", profile.music.durationSecondsFieldName)
    }

    @Test
    fun `uses the latest verified template for unknown Kuwo versions`() {
        assertEquals(KuwoHookProfiles.V12_2_2_0, KuwoHookProfiles.resolve("12.2.0.0", 12_200L))
        assertEquals(KuwoHookProfiles.V12_2_2_0, KuwoHookProfiles.resolve("12.2.3.0", 12_230L))
    }

    @Test
    fun `anchors Kuwo recovery to original DEX semantics`() {
        val queries = KuwoNextTrackResolver.queries(KuwoHookProfiles.V12_1_8_2)
        val next = queries[0]
        val singleton = queries[1]
        val current = queries[2]

        assertEquals("kuwo-next-content-v3", next.cacheKey)
        assertEquals(
            listOf("随机模式，获取歌曲下一曲,随机索引空，现在生成"),
            next.requiredStrings,
        )
        assertEquals("cn.kuwo.base.bean.IContent", next.returnTypeName)
        assertFalse(next.isStatic ?: true)
        assertEquals(next.cacheKey, singleton.declaringClassReference?.queryCacheKey)
        assertEquals(next.cacheKey, current.declaringClassReference?.queryCacheKey)
        assertTrue(current.requiredCallerMethodNames.isEmpty())
        assertEquals("cn.kuwo.base.bean.Music", current.returnTypeName)
    }

    @Test
    fun `debounces transient Kuwo queue alignment failures`() {
        val tracker = KuwoNextTrackValidationTracker(invalidThreshold = 3)

        assertFalse(tracker.record(alignmentFailed = true))
        assertFalse(tracker.record(alignmentFailed = true))
        assertTrue(tracker.record(alignmentFailed = true))
        assertFalse(tracker.record(alignmentFailed = false))
        assertFalse(tracker.record(alignmentFailed = true))
    }
}
