/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicRuntimePlanTest {
    @Test
    fun `routes mobile main process to next track only`() {
        assertEquals(
            setOf(QQMusicRuntimeFeature.NEXT_TRACK),
            QQMusicRuntimePlan.resolve(
                QQMusicRuntimePlan.MOBILE_PACKAGE,
                QQMusicRuntimePlan.MOBILE_PACKAGE,
            ),
        )
    }

    @Test
    fun `routes mobile player process to lyrics and buffering state`() {
        assertEquals(
            setOf(QQMusicRuntimeFeature.LYRICS, QQMusicRuntimeFeature.BUFFERING_STATE),
            QQMusicRuntimePlan.resolve(
                QQMusicRuntimePlan.MOBILE_PACKAGE,
                "${QQMusicRuntimePlan.MOBILE_PACKAGE}:QQPlayerService",
            ),
        )
    }

    @Test
    fun `routes HD main process to lyrics and next track`() {
        assertEquals(
            setOf(QQMusicRuntimeFeature.LYRICS, QQMusicRuntimeFeature.NEXT_TRACK),
            QQMusicRuntimePlan.resolve(
                QQMusicRuntimePlan.HD_PACKAGE,
                QQMusicRuntimePlan.HD_PACKAGE,
            ),
        )
    }

    @Test
    fun `routes miui main process to next track only`() {
        assertEquals(
            setOf(QQMusicRuntimeFeature.NEXT_TRACK),
            QQMusicRuntimePlan.resolve(
                QQMusicRuntimePlan.MIUI_PACKAGE,
                QQMusicRuntimePlan.MIUI_PACKAGE,
            ),
        )
    }

    @Test
    fun `routes miui remote process to lyrics only`() {
        assertEquals(
            setOf(QQMusicRuntimeFeature.LYRICS),
            QQMusicRuntimePlan.resolve(
                QQMusicRuntimePlan.MIUI_PACKAGE,
                "${QQMusicRuntimePlan.MIUI_PACKAGE}:remote",
            ),
        )
    }

    @Test
    fun `rejects unsupported packages and undeclared processes`() {
        assertTrue(QQMusicRuntimePlan.supports(QQMusicRuntimePlan.MOBILE_PACKAGE))
        assertTrue(QQMusicRuntimePlan.supports(QQMusicRuntimePlan.HD_PACKAGE))
        assertTrue(QQMusicRuntimePlan.supports(QQMusicRuntimePlan.MIUI_PACKAGE))
        assertFalse(QQMusicRuntimePlan.supports("com.example.music"))
        assertTrue(
            QQMusicRuntimePlan.resolve(
                QQMusicRuntimePlan.HD_PACKAGE,
                "${QQMusicRuntimePlan.HD_PACKAGE}:push",
            ).isEmpty(),
        )
        assertTrue(
            QQMusicRuntimePlan.resolve(
                QQMusicRuntimePlan.MIUI_PACKAGE,
                "${QQMusicRuntimePlan.MIUI_PACKAGE}:pushservice",
            ).isEmpty(),
        )
    }
}
