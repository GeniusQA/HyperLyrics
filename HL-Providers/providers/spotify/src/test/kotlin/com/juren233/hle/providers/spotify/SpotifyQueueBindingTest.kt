/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyQueueBindingTest {
    @Test
    fun `normalizes only verified Spotify track identities for active requests`() {
        assertEquals(
            "spotify:track:AAAAAAAAAAAAAAAAAAAAAA",
            SpotifyTrackIdentity.requestUri("https://open.spotify.com/track/AAAAAAAAAAAAAAAAAAAAAA?si=x"),
        )
        assertEquals(
            "spotify:track:BBBBBBBBBBBBBBBBBBBBBB",
            SpotifyTrackIdentity.requestUri("spotify:track:BBBBBBBBBBBBBBBBBBBBBB"),
        )
        assertEquals(
            "spotify:track:CCCCCCCCCCCCCCCCCCCCCC",
            SpotifyTrackIdentity.requestUri("CCCCCCCCCCCCCCCCCCCCCC"),
        )
        assertEquals(null, SpotifyTrackIdentity.requestUri("spotify:episode:short"))
        assertEquals(
            null,
            SpotifyTrackIdentity.requestUri("spotify:episode:DDDDDDDDDDDDDDDDDDDDDD"),
        )
    }

    @Test
    fun `aligns base62 MediaSession id with Spotify URI`() {
        val snapshot = snapshot("spotify:track:1234567890123456789012", "Current")
        val metadata = SpotifyTrackMetadata(
            mediaId = "1234567890123456789012",
            title = "Current",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
        )

        assertEquals(snapshot, SpotifyQueueBinding.align(metadata, snapshot))
    }

    @Test
    fun `rejects stale queue state after a track switch`() {
        val metadata = SpotifyTrackMetadata(
            mediaId = "AAAAAAAAAAAAAAAAAAAAAA",
            title = "New",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
        )

        assertNull(
            SpotifyQueueBinding.align(
                metadata,
                snapshot("spotify:track:BBBBBBBBBBBBBBBBBBBBBB", "Old"),
            ),
        )
    }

    @Test
    fun `treats metadata completion without an initial id as the same song`() {
        val initial = SpotifyTrackMetadata(
            mediaId = null,
            title = "Current",
            artist = "Artist",
            album = null,
            durationMs = 0L,
        )
        val completed = SpotifyTrackMetadata(
            mediaId = "spotify:track:1234567890123456789012",
            title = "Current",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
        )

        assertEquals(true, SpotifyTrackIdentity.sameTrack(initial, completed))
    }

    @Test
    fun `does not collapse different ids with the same title`() {
        val first = SpotifyTrackMetadata(
            "spotify:track:AAAAAAAAAAAAAAAAAAAAAA",
            "Current",
            "Artist",
            null,
            180_000L,
        )
        val second = SpotifyTrackMetadata(
            "spotify:track:BBBBBBBBBBBBBBBBBBBBBB",
            "Current",
            "Artist",
            null,
            180_000L,
        )

        assertEquals(false, SpotifyTrackIdentity.sameTrack(first, second))
    }

    private fun snapshot(currentId: String, currentTitle: String) = SpotifyQueueSnapshot(
        current = SpotifyTrackSnapshot(
            id = currentId,
            title = currentTitle,
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
        ),
        next = SpotifyTrackSnapshot(
            id = "spotify:track:CCCCCCCCCCCCCCCCCCCCCC",
            title = "Next",
            artist = "Next Artist",
            album = "Next Album",
            durationMs = 200_000L,
        ),
    )
}
