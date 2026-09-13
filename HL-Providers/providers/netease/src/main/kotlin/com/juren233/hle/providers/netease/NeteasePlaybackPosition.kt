/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

internal const val NETEASE_POSITION_UPDATE_INTERVAL_MS = 1_000L / 24L

internal fun extrapolatePlaybackPosition(
    basePosition: Long,
    lastUpdateTime: Long,
    now: Long,
    playbackSpeed: Float,
    playing: Boolean,
): Long {
    val safeBase = basePosition.coerceAtLeast(0L)
    if (!playing || lastUpdateTime <= 0L) return safeBase

    val elapsed = (now - lastUpdateTime).coerceAtLeast(0L)
    val safeSpeed = playbackSpeed.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    return (safeBase + (elapsed * safeSpeed).toLong()).coerceAtLeast(safeBase)
}
