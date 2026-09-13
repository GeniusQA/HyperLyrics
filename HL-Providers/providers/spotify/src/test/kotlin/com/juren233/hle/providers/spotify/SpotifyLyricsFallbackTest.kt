/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyLyricsFallbackTest {
    @Test
    fun `track change cancels old delay and active request`() {
        val fixture = Fixture()
        fixture.coordinator.onClientAvailable(CLIENT)
        fixture.coordinator.onTrackChanged(TRACK_A)

        fixture.scheduler.advanceBy(1_200L)
        assertEquals(listOf(TRACK_A), fixture.requester.startedTracks)
        assertFalse(fixture.requester.requests.single().cancellation.cancelled)

        fixture.coordinator.onTrackChanged(TRACK_B)

        assertTrue(fixture.requester.requests.single().cancellation.cancelled)
        fixture.scheduler.advanceBy(1_200L)
        assertEquals(listOf(TRACK_A, TRACK_B), fixture.requester.startedTracks)
    }

    @Test
    fun `same song metadata completion does not schedule twice`() {
        val fixture = Fixture()
        fixture.coordinator.onClientAvailable(CLIENT)
        fixture.coordinator.onTrackChanged(null)
        fixture.coordinator.onTrackMetadataUpdated(TRACK_A)
        fixture.coordinator.onTrackMetadataUpdated(TRACK_A)

        fixture.scheduler.advanceBy(1_200L)
        fixture.coordinator.onTrackMetadataUpdated(TRACK_A)
        fixture.scheduler.advanceBy(5_000L)

        assertEquals(listOf(TRACK_A), fixture.requester.startedTracks)
    }

    @Test
    fun `official lyrics arriving first cancel fallback`() {
        val fixture = Fixture()
        fixture.coordinator.onClientAvailable(CLIENT)
        fixture.coordinator.onTrackChanged(TRACK_A)

        fixture.scheduler.advanceBy(400L)
        fixture.coordinator.onLyricsAvailable(TRACK_A)
        fixture.scheduler.advanceBy(2_000L)

        assertEquals(emptyList<String>(), fixture.requester.startedTracks)
    }

    @Test
    fun `official lyrics cancel an already active fallback subscription`() {
        val fixture = Fixture()
        fixture.coordinator.onClientAvailable(CLIENT)
        fixture.coordinator.onTrackChanged(TRACK_A)
        fixture.scheduler.advanceBy(1_200L)
        val request = fixture.requester.requests.single()

        fixture.coordinator.onLyricsAvailable(TRACK_A)

        assertTrue(request.cancellation.cancelled)
        request.success("late-value")
        assertEquals(emptyList<Pair<String, Any>>(), fixture.successes)
    }

    @Test
    fun `client arriving after metadata still starts one request`() {
        val fixture = Fixture()
        fixture.coordinator.onTrackChanged(TRACK_A)
        fixture.scheduler.advanceBy(2_000L)

        fixture.coordinator.onClientAvailable(CLIENT)
        fixture.scheduler.runCurrent()

        assertEquals(listOf(TRACK_A), fixture.requester.startedTracks)
    }

    @Test
    fun `each track attempts active request at most once`() {
        val fixture = Fixture()
        fixture.coordinator.onClientAvailable(CLIENT)
        fixture.coordinator.onTrackChanged(TRACK_A)
        fixture.scheduler.advanceBy(1_200L)
        fixture.requester.requests.single().error(IllegalStateException("network"))

        fixture.coordinator.onTrackMetadataUpdated(TRACK_A)
        fixture.coordinator.onClientAvailable(Any())
        fixture.scheduler.advanceBy(5_000L)

        assertEquals(listOf(TRACK_A), fixture.requester.startedTracks)
    }

    @Test
    fun `old song async success cannot reach current song`() {
        val fixture = Fixture()
        fixture.coordinator.onClientAvailable(CLIENT)
        fixture.coordinator.onTrackChanged(TRACK_A)
        fixture.scheduler.advanceBy(1_200L)
        val oldRequest = fixture.requester.requests.single()

        fixture.coordinator.onTrackChanged(TRACK_B)
        oldRequest.success("old-value")

        assertEquals(emptyList<Pair<String, Any>>(), fixture.successes)
    }

    private class Fixture {
        val scheduler = FakeScheduler()
        val requester = FakeRequester()
        val successes = mutableListOf<Pair<String, Any>>()
        val coordinator = SpotifyLyricsFallbackCoordinator(
            scheduler = scheduler,
            requestDelayMs = 1_200L,
            requestStarter = requester,
            onSuccess = { trackUri, value -> successes += trackUri to value },
        )
    }

    private class FakeRequester : SpotifyLyricsFallbackRequestStarter<Any> {
        val requests = mutableListOf<Request>()
        val startedTracks: List<String>
            get() = requests.map(Request::trackUri)

        override fun start(
            client: Any,
            trackUri: String,
            onSuccess: (Any) -> Unit,
            onError: (Throwable) -> Unit,
        ): SpotifyLyricsFallbackCancellation {
            val cancellation = FakeCancellation()
            requests += Request(trackUri, onSuccess, onError, cancellation)
            return cancellation
        }
    }

    private data class Request(
        val trackUri: String,
        val success: (Any) -> Unit,
        val error: (Throwable) -> Unit,
        val cancellation: FakeCancellation,
    )

    private class FakeScheduler : SpotifyLyricsFallbackScheduler {
        private var now = 0L
        private val tasks = mutableListOf<Task>()

        override fun nowMs(): Long = now

        override fun schedule(
            delayMs: Long,
            action: () -> Unit,
        ): SpotifyLyricsFallbackCancellation {
            val task = Task(now + delayMs, action)
            tasks += task
            return SpotifyLyricsFallbackCancellation { task.cancelled = true }
        }

        fun advanceBy(deltaMs: Long) {
            now += deltaMs
            runCurrent()
        }

        fun runCurrent() {
            while (true) {
                val task = tasks
                    .filterNot(Task::cancelled)
                    .filter { it.runAtMs <= now }
                    .minByOrNull(Task::runAtMs)
                    ?: return
                tasks.remove(task)
                task.action()
            }
        }
    }

    private data class Task(
        val runAtMs: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private class FakeCancellation : SpotifyLyricsFallbackCancellation {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    private companion object {
        val CLIENT = Any()
        const val TRACK_A = "spotify:track:AAAAAAAAAAAAAAAAAAAAAA"
        const val TRACK_B = "spotify:track:BBBBBBBBBBBBBBBBBBBBBB"
    }
}
