/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KuwoTrackIdResolverTest {
    @Test
    fun `uses positive numeric MediaSession id directly`() {
        assertEquals(81_466_699L, KuwoTrackIdResolver.directRid("81466699"))
        assertEquals(81_466_699L, KuwoTrackIdResolver.directRid("MUSIC_81466699"))
        assertNull(KuwoTrackIdResolver.directRid("-1"))
        assertNull(KuwoTrackIdResolver.directRid("local-song"))
    }

    @Test
    fun `search fallback prefers matching title artist and duration`() {
        val track = KuwoTrackMetadata(
            mediaId = null,
            title = "everything i wanted",
            artist = "Billie Eilish",
            album = "everything i wanted",
            durationMs = 245_000L,
        )
        val selected = KuwoTrackIdResolver.chooseCandidate(
            track,
            listOf(
                KuwoSearchCandidate(1L, "everything i wanted", "Other Artist", null, 245L),
                KuwoSearchCandidate(
                    81_466_699L,
                    "Everything I Wanted",
                    "Billie Eilish",
                    "everything i wanted",
                    245L,
                ),
            ),
        )

        assertEquals(81_466_699L, selected?.rid)
    }

    @Test
    fun `rejects an exact title owned by a different artist`() {
        val track = KuwoTrackMetadata(null, "Without You", "Avicii", null, 181_000L)
        val selected = KuwoTrackIdResolver.chooseCandidate(
            track,
            listOf(KuwoSearchCandidate(7L, "Without You", "Other Artist", null, 181L)),
        )

        assertNull(selected)
    }
}
