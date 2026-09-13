/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyCurrentSongReplayTest {
    @Test
    fun `control frames cannot replace the remembered current song`() {
        val sentSongs = mutableListOf<String>()
        val sentFrames = mutableListOf<String>()
        val replayedSongs = mutableListOf<String>()
        val publisher = SpotifyPluginEntry.SpotifyProviderPublisher<String>(
            songSender = { sentSongs += it; true },
            controlSender = { sentFrames += it; true },
        )

        publisher.publishSong("song-a")
        publisher.publishControlFrame("next-track-frame")
        val replay = publisher.replaySong { replayedSongs += it; true }

        assertEquals(listOf("song-a"), sentSongs)
        assertEquals(listOf("next-track-frame"), sentFrames)
        assertEquals(listOf("song-a"), replayedSongs)
        assertEquals("song-a", replay?.first)
        assertEquals(true, replay?.second)
    }

    @Test
    fun `later song replaces the previous reconnect song`() {
        val publisher = SpotifyPluginEntry.SpotifyProviderPublisher<String>(
            songSender = { true },
            controlSender = { true },
        )

        publisher.publishSong("song-a")
        publisher.publishSong("song-b")

        assertEquals("song-b", publisher.currentSong())
    }

    @Test
    fun `metadata clear prevents replaying a stale song`() {
        val publisher = SpotifyPluginEntry.SpotifyProviderPublisher<String>(
            songSender = { true },
            controlSender = { true },
        )

        publisher.publishSong("song-a")
        publisher.clearSong()

        assertNull(publisher.currentSong())
    }
}
