/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicBufferHookResolverTest {
    @Test
    fun `mobile queries use verified binary targets and semantic repair strings`() {
        val queries = QQMusicBufferHookResolver.queries(
            packageName = QQMusicRuntimePlan.MOBILE_PACKAGE,
            versionName = "20.6.5.8",
            versionCode = 7_228L,
        )!!

        assertEquals(2, queries.size)
        assertEquals(
            listOf("buffer started.", "buffer ended."),
            queries.map { it.requiredStrings.single() },
        )
        assertTrue(queries.all { it.preferredTarget?.className == "com.tencent.qqmusic.mediaplayer.i\$c" })
        assertEquals(listOf("long"), queries[0].preferredTarget?.parameterTypeNames)
        assertEquals(
            listOf(
                "long",
                "int",
                "com.tencent.qqmusic.mediaplayer.upstream.ReadWaitEndStatus",
            ),
            queries[1].preferredTarget?.parameterTypeNames,
        )
        assertTrue(queries.all { it.declaringClassName == null })
    }

    @Test
    fun `unverified mobile versions go directly through semantic DexKit queries`() {
        val queries = QQMusicBufferHookResolver.queries(
            packageName = QQMusicRuntimePlan.MOBILE_PACKAGE,
            versionName = "20.7.0.8",
            versionCode = 7_300L,
        )!!

        assertTrue(queries.all { it.preferredTarget == null })
        assertEquals(
            listOf("buffer started.", "buffer ended."),
            queries.map { it.requiredStrings.single() },
        )
    }

    @Test
    fun `HD does not install the mobile buffering callbacks`() {
        assertNull(
            QQMusicBufferHookResolver.queries(
                packageName = QQMusicRuntimePlan.HD_PACKAGE,
                versionName = "6.12.0.5",
                versionCode = 6_120_005L,
            ),
        )
    }
}
