/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SaltPlayerNextTrackResolverTest {
    @Test
    fun `uses a stable method shape to recover the controller class`() {
        val query = SaltPlayerNextTrackResolver.controllerFallbackQuery(
            SaltPlayerHookProfiles.V12_1_1,
        )

        assertEquals(
            SaltPlayerNextTrackResolver.CONTROLLER_FALLBACK_CACHE_KEY,
            query.cacheKey,
        )
        assertEquals("com.salt.music.service.", query.declaringClassNamePrefix)
        assertEquals(
            listOf("com.salt.music.data.entry.Song", "long", "long", "java.lang.Long"),
            query.parameterTypeNames,
        )
        assertEquals("void", query.returnTypeName)
        assertEquals(true, query.isStatic)
    }

    @Test
    fun `initializes the queue owner class on the calling thread`() {
        InitializationProbeState.initializedThread = null

        SaltPlayerNextTrackResolver.loadInitializedClass(
            "com.juren233.hle.providers.saltplayer.SaltPlayerNextTrackInitializationProbe",
            javaClass.classLoader!!,
        )

        assertSame(Thread.currentThread(), InitializationProbeState.initializedThread)
    }

    @Test
    fun `selects the following item from the normal queue`() {
        val state = queueState(
            mode = SaltPlayerPlaybackMode.CIRCLE,
            normal = listOf(track("1"), track("2"), track("3")),
            normalIndex = 1,
        )

        assertEquals("3", SaltPlayerNextTrackSelector.select(state, "2")?.id)
    }

    @Test
    fun `wraps the normal queue at the end`() {
        val state = queueState(
            mode = SaltPlayerPlaybackMode.CIRCLE_END,
            normal = listOf(track("1"), track("2")),
            normalIndex = 1,
        )

        assertEquals("1", SaltPlayerNextTrackSelector.select(state, "2")?.id)
    }

    @Test
    fun `uses the random queue and suppresses its multi-item tail`() {
        val state = queueState(
            mode = SaltPlayerPlaybackMode.RANDOM,
            random = listOf(track("3"), track("1"), track("2")),
            randomIndex = 2,
        )

        assertNull(SaltPlayerNextTrackSelector.select(state, "2"))
    }

    @Test
    fun `single random item wraps to itself`() {
        val state = queueState(
            mode = SaltPlayerPlaybackMode.RANDOM,
            random = listOf(track("1")),
            randomIndex = 0,
        )

        assertEquals("1", SaltPlayerNextTrackSelector.select(state, "1")?.id)
    }

    @Test
    fun `repeat one uses current media metadata even without a queue`() {
        val current = track("current", title = "Current song")
        val state = queueState(mode = SaltPlayerPlaybackMode.REPEAT_ONE)

        assertEquals(current, SaltPlayerNextTrackSelector.select(state, "current", current))
    }

    @Test
    fun `relocates a stale queue index using the current song id`() {
        val state = queueState(
            mode = SaltPlayerPlaybackMode.CIRCLE,
            normal = listOf(track("1"), track("2"), track("3")),
            normalIndex = 0,
        )

        assertEquals("3", SaltPlayerNextTrackSelector.select(state, "2")?.id)
    }

    @Test
    fun `rejects an unknown current id instead of publishing the wrong next song`() {
        val state = queueState(
            mode = SaltPlayerPlaybackMode.CIRCLE,
            normal = listOf(track("1"), track("2")),
            normalIndex = 0,
        )

        assertNull(SaltPlayerNextTrackSelector.select(state, "missing"))
    }

    private fun queueState(
        mode: SaltPlayerPlaybackMode,
        normal: List<SaltPlayerTrackSnapshot> = emptyList(),
        normalIndex: Int = -1,
        random: List<SaltPlayerTrackSnapshot> = emptyList(),
        randomIndex: Int = -1,
    ) = SaltPlayerQueueStateSnapshot(
        mode = mode,
        normalQueue = normal,
        normalIndex = normalIndex,
        randomQueue = random,
        randomIndex = randomIndex,
        readyToSave = true,
    )

    private fun track(
        id: String,
        title: String = "Song $id",
    ) = SaltPlayerTrackSnapshot(
        id = id,
        title = title,
        artist = "Artist $id",
        album = "Album $id",
        durationMs = 180_000L,
    )
}

private object InitializationProbeState {
    @Volatile
    var initializedThread: Thread? = null
}

private class SaltPlayerNextTrackInitializationProbe {
    companion object {
        init {
            InitializationProbeState.initializedThread = Thread.currentThread()
        }
    }
}
