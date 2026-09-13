/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import java.util.concurrent.atomic.AtomicLong

/** Debug-only correlation and bounded samples; never used to choose playback behavior. */
internal class NeteasePlaybackDiagnosticSampler {
    private val sequence = AtomicLong()
    val currentSequence: Long get() = sequence.get()
    fun nextSequence(): Long = sequence.incrementAndGet()

    private var lastWriteSequence: Long? = null
    private var lastWriteResult: Boolean? = null
    private var lastWriteAt: Long? = null
    private var lastExitSequence: Long? = null
    private var lastExitReason: String? = null

    @Synchronized
    fun sampleWrite(now: Long, callback: Long, result: Boolean): String? {
        val reason = when {
            lastWriteAt == null -> "first_write"
            callback != lastWriteSequence -> "callback_changed"
            result != lastWriteResult -> "result_changed"
            now - requireNotNull(lastWriteAt) >= 5_000L -> "periodic"
            else -> return null
        }
        lastWriteSequence = callback
        lastWriteResult = result
        lastWriteAt = now
        return reason
    }

    @Synchronized
    fun sampleExit(callback: Long, reason: String): Boolean {
        if (callback == lastExitSequence && reason == lastExitReason) return false
        lastExitSequence = callback
        lastExitReason = reason
        return true
    }
}
