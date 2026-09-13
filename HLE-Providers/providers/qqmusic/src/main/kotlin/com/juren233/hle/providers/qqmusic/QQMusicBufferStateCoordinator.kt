/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.media.session.PlaybackState

internal data class QQMusicPlaybackSnapshot(
    val state: Int,
    val position: Long,
    val updatedAtMs: Long,
    val speed: Float,
)

internal sealed interface QQMusicPlaybackDecision {
    data object Forward : QQMusicPlaybackDecision
    data object Ignore : QQMusicPlaybackDecision
    data class Publish(val state: QQMusicPlaybackSnapshot) : QQMusicPlaybackDecision
}

/**
 * Converts QQ Music's internal buffer boundary into an authoritative PlaybackState boundary.
 *
 * QQ Music keeps its public MediaSession in PLAYING while network reads are blocked. The explicit
 * internal callbacks therefore own the synthetic BUFFERING lifetime; ordinary MediaSession anchor
 * age is never used as a buffering heuristic.
 */
internal class QQMusicBufferStateCoordinator {
    private var latestRealState: QQMusicPlaybackSnapshot? = null
    private var frozenPosition: Long? = null

    @Synchronized
    fun onPlaybackState(state: QQMusicPlaybackSnapshot?): QQMusicPlaybackDecision {
        latestRealState = state
        if (state == null) {
            frozenPosition = null
            return QQMusicPlaybackDecision.Forward
        }

        val frozen = frozenPosition
        if (frozen != null && state.state == PlaybackState.STATE_PLAYING) {
            return QQMusicPlaybackDecision.Publish(
                QQMusicPlaybackSnapshot(
                    state = PlaybackState.STATE_BUFFERING,
                    position = frozen,
                    updatedAtMs = state.updatedAtMs,
                    speed = 0f,
                ),
            )
        }

        if (state.state != PlaybackState.STATE_PLAYING) {
            frozenPosition = null
        }
        return QQMusicPlaybackDecision.Forward
    }

    @Synchronized
    fun onBufferStarted(nowMs: Long): QQMusicPlaybackDecision {
        if (frozenPosition != null) return QQMusicPlaybackDecision.Ignore
        val realState = latestRealState ?: return QQMusicPlaybackDecision.Ignore
        if (realState.state != PlaybackState.STATE_PLAYING) {
            return QQMusicPlaybackDecision.Ignore
        }

        val position = realState.positionAt(nowMs)
        frozenPosition = position
        return QQMusicPlaybackDecision.Publish(
            QQMusicPlaybackSnapshot(
                state = PlaybackState.STATE_BUFFERING,
                position = position,
                updatedAtMs = nowMs,
                speed = 0f,
            ),
        )
    }

    @Synchronized
    fun onBufferEnded(nowMs: Long): QQMusicPlaybackDecision {
        val position = frozenPosition ?: return QQMusicPlaybackDecision.Ignore
        frozenPosition = null
        val realState = latestRealState
        if (realState?.state != PlaybackState.STATE_PLAYING) {
            return QQMusicPlaybackDecision.Ignore
        }

        return QQMusicPlaybackDecision.Publish(
            QQMusicPlaybackSnapshot(
                state = PlaybackState.STATE_PLAYING,
                position = position,
                updatedAtMs = nowMs,
                speed = realState.speed.takeIf { it > 0f } ?: 1f,
            ),
        )
    }

    @Synchronized
    fun reset() {
        frozenPosition = null
    }

    private fun QQMusicPlaybackSnapshot.positionAt(nowMs: Long): Long {
        val base = position.coerceAtLeast(0L)
        if (updatedAtMs <= 0L || speed <= 0f) return base
        val elapsed = (nowMs - updatedAtMs).coerceAtLeast(0L)
        return if (speed == 1f) base + elapsed else base + (elapsed * speed).toLong()
    }
}
