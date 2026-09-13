/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicBufferStateCoordinatorTest {
    @Test
    fun `freezes at the real playing position when buffer starts`() {
        val coordinator = QQMusicBufferStateCoordinator()
        assertEquals(QQMusicPlaybackDecision.Forward, coordinator.onPlaybackState(playing()))

        val decision = coordinator.onBufferStarted(nowMs = 12_500L)
            as QQMusicPlaybackDecision.Publish

        assertEquals(PlaybackState.STATE_BUFFERING, decision.state.state)
        assertEquals(3_500L, decision.state.position)
        assertEquals(12_500L, decision.state.updatedAtMs)
        assertEquals(0f, decision.state.speed)
    }

    @Test
    fun `repeated playing callbacks cannot unfreeze an active buffer`() {
        val coordinator = QQMusicBufferStateCoordinator()
        coordinator.onPlaybackState(playing())
        coordinator.onBufferStarted(nowMs = 12_500L)

        val decision = coordinator.onPlaybackState(
            playing(position = 4_000L, updatedAtMs = 13_000L),
        ) as QQMusicPlaybackDecision.Publish

        assertEquals(PlaybackState.STATE_BUFFERING, decision.state.state)
        assertEquals(3_500L, decision.state.position)
        assertEquals(0f, decision.state.speed)
    }

    @Test
    fun `buffer end resumes from the frozen anchor instead of counting stalled time`() {
        val coordinator = QQMusicBufferStateCoordinator()
        coordinator.onPlaybackState(playing())
        coordinator.onBufferStarted(nowMs = 12_500L)

        val decision = coordinator.onBufferEnded(nowMs = 42_500L)
            as QQMusicPlaybackDecision.Publish

        assertEquals(PlaybackState.STATE_PLAYING, decision.state.state)
        assertEquals(3_500L, decision.state.position)
        assertEquals(42_500L, decision.state.updatedAtMs)
        assertEquals(1f, decision.state.speed)
    }

    @Test
    fun `pause cancels the synthetic buffer and late end is ignored`() {
        val coordinator = QQMusicBufferStateCoordinator()
        coordinator.onPlaybackState(playing())
        coordinator.onBufferStarted(nowMs = 12_500L)

        assertEquals(
            QQMusicPlaybackDecision.Forward,
            coordinator.onPlaybackState(
                QQMusicPlaybackSnapshot(
                    state = PlaybackState.STATE_PAUSED,
                    position = 3_500L,
                    updatedAtMs = 13_000L,
                    speed = 0f,
                ),
            ),
        )
        assertEquals(QQMusicPlaybackDecision.Ignore, coordinator.onBufferEnded(42_500L))
    }

    @Test
    fun `buffer callbacks do not synthesize playback without a real playing state`() {
        val coordinator = QQMusicBufferStateCoordinator()

        assertEquals(QQMusicPlaybackDecision.Ignore, coordinator.onBufferStarted(12_500L))
        assertEquals(QQMusicPlaybackDecision.Ignore, coordinator.onBufferEnded(13_000L))
        assertTrue(coordinator.onPlaybackState(null) is QQMusicPlaybackDecision.Forward)
    }

    private fun playing(
        position: Long = 1_000L,
        updatedAtMs: Long = 10_000L,
    ) = QQMusicPlaybackSnapshot(
        state = PlaybackState.STATE_PLAYING,
        position = position,
        updatedAtMs = updatedAtMs,
        speed = 1f,
    )
}
