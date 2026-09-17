/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouVersionProfileTest {

    @Test
    fun `concept edition below calibrated baseline uses legacy fallback`() {
        // 设备取证：概念版 2.5.5 (versionCode 10597) 的歌词类完全混淆，
        // 不存在 LyricManager / QueuePlayerManager / IMedia。
        assertFalse(
            KuGouVersionProfile.supportsModernDexProfiles(
                KuGouVersionProfile.LITE_PACKAGE,
                10_597L,
            ),
        )
    }

    @Test
    fun `concept edition at or above calibrated baseline uses semantic anchors`() {
        // 标定版本：概念版 5.2.4 (versionCode 11540)
        assertTrue(
            KuGouVersionProfile.supportsModernDexProfiles(
                KuGouVersionProfile.LITE_PACKAGE,
                KuGouVersionProfile.LITE_MODERN_MIN_VERSION_CODE,
            ),
        )
        assertTrue(
            KuGouVersionProfile.supportsModernDexProfiles(
                KuGouVersionProfile.LITE_PACKAGE,
                12_000L,
            ),
        )
    }

    @Test
    fun `full player baseline follows its own calibrated version code`() {
        assertTrue(
            KuGouVersionProfile.supportsModernDexProfiles(
                KuGouVersionProfile.FULL_PACKAGE,
                KuGouVersionProfile.FULL_MODERN_MIN_VERSION_CODE,
            ),
        )
        assertFalse(
            KuGouVersionProfile.supportsModernDexProfiles(
                KuGouVersionProfile.FULL_PACKAGE,
                20_000L,
            ),
        )
    }

    @Test
    fun `unknown package never claims semantic anchor support`() {
        assertFalse(KuGouVersionProfile.supportsModernDexProfiles("com.example.player", 99_999L))
    }
}

class KuGouLyricFilePathPolicyTest {

    @Test
    fun `accepts kugou lyric file extensions`() {
        assertTrue(KuGouLyricFilePathPolicy.isLyricFilePath("/sdcard/kg/lyric/abc.krc"))
        assertTrue(KuGouLyricFilePathPolicy.isLyricFilePath("/data/user/0/pkg/cache/x.LRC"))
        assertTrue(KuGouLyricFilePathPolicy.isLyricFilePath("abc.Krc"))
    }

    @Test
    fun `rejects non lyric files`() {
        assertFalse(KuGouLyricFilePathPolicy.isLyricFilePath("/sdcard/kg/abc.mp3"))
        assertFalse(KuGouLyricFilePathPolicy.isLyricFilePath("/sdcard/kg/abc.txt"))
        assertFalse(KuGouLyricFilePathPolicy.isLyricFilePath("/sdcard/kg/abc.krc.bak"))
        assertFalse(KuGouLyricFilePathPolicy.isLyricFilePath(""))
        assertFalse(KuGouLyricFilePathPolicy.isLyricFilePath(null))
    }
}

class KuGouLyricFileHitGuardTest {

    @Test
    fun `repeated open of same path inside ttl window is ignored`() {
        val guard = KuGouLyricFileHitGuard()
        assertTrue(guard.shouldHandle("/x/a.krc", 1_000L))
        assertFalse(guard.shouldHandle("/x/a.krc", 1_500L))
        // 元数据未就绪被放弃的场景：同一路径在时间窗外允许重新解析
        assertTrue(guard.shouldHandle("/x/a.krc", 4_000L))
    }

    @Test
    fun `different paths are tracked independently`() {
        val guard = KuGouLyricFileHitGuard()
        assertTrue(guard.shouldHandle("/x/a.krc", 1_000L))
        assertTrue(guard.shouldHandle("/x/a.lrc", 1_100L))
    }

    @Test
    fun `capacity is bounded so earliest record is evicted`() {
        val guard = KuGouLyricFileHitGuard(capacity = 2, ttlMs = 1_000L)
        assertTrue(guard.shouldHandle("/x/1.krc", 10L))
        assertTrue(guard.shouldHandle("/x/2.krc", 20L))
        assertTrue(guard.shouldHandle("/x/3.krc", 30L))
        // 时间窗内被淘汰过的路径可再次命中（未淘汰时应返回 false）
        assertTrue(guard.shouldHandle("/x/1.krc", 40L))
        // 仍在记录中的路径在时间窗内继续被忽略
        assertFalse(guard.shouldHandle("/x/3.krc", 50L))
    }
}
