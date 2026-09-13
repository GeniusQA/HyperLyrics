/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaltPlayerMediaStoreResolverTest {
    @Test
    fun `selects a unique title and duration match`() {
        val selected = SaltPlayerMediaCandidateSelector.select(
            metadata(),
            listOf(candidate(id = 1L)),
        )

        assertEquals(1L, selected?.mediaId)
    }

    @Test
    fun `rejects indistinguishable duplicate files`() {
        val selected = SaltPlayerMediaCandidateSelector.select(
            metadata(),
            listOf(candidate(id = 1L), candidate(id = 2L)),
        )

        assertNull(selected)
    }

    @Test
    fun `uses matching album to disambiguate same title and duration`() {
        val selected = SaltPlayerMediaCandidateSelector.select(
            metadata(album = "Expected album"),
            listOf(
                candidate(id = 1L, album = "Other album"),
                candidate(id = 2L, album = "Expected album"),
            ),
        )

        assertEquals(2L, selected?.mediaId)
    }

    @Test
    fun `rejects a duration outside the high confidence window`() {
        val selected = SaltPlayerMediaCandidateSelector.select(
            metadata(durationMs = 180_000L),
            listOf(candidate(id = 1L, durationMs = 183_000L)),
        )

        assertNull(selected)
    }

    @Test
    fun `rejects fallback matching without a published duration`() {
        val selected = SaltPlayerMediaCandidateSelector.select(
            metadata(durationMs = 0L),
            listOf(candidate(id = 1L)),
        )

        assertNull(selected)
    }

    @Test
    fun `normalizes unicode width whitespace and title case`() {
        val selected = SaltPlayerMediaCandidateSelector.select(
            metadata(title = "Ｔｅｓｔ   Song"),
            listOf(candidate(id = 8L, title = "test song")),
        )

        assertEquals(8L, selected?.mediaId)
    }

    private fun metadata(
        title: String = "Test Song",
        artist: String = "Artist",
        album: String = "Album",
        durationMs: Long = 180_000L,
    ) = SaltPlayerTrackMetadata(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
    )

    private fun candidate(
        id: Long,
        title: String = "Test Song",
        artist: String = "Artist",
        album: String = "Album",
        durationMs: Long = 180_000L,
    ) = SaltPlayerMediaCandidate(
        mediaUri = "content://media/external/audio/media/$id",
        mediaId = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        displayName = "$title.flac",
        relativePath = "Music/",
        dataPath = null,
    )
}
