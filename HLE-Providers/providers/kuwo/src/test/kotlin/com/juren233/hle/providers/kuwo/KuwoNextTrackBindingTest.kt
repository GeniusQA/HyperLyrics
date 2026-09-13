/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KuwoNextTrackBindingTest {
    @Test
    fun `accepts queue snapshot when current rid matches MediaSession`() {
        val snapshot = queue(currentId = "81466699", nextId = "28512333")

        assertEquals(
            "28512333",
            KuwoNextTrackBinding.align(metadata("81466699", "everything i wanted"), snapshot)
                ?.next
                ?.id,
        )
    }

    @Test
    fun `rejects stale queue snapshot after MediaSession switches tracks`() {
        val stale = queue(currentId = "81466699", nextId = "28512333")

        assertNull(
            KuwoNextTrackBinding.align(metadata("28512333", "Without You"), stale),
        )
    }

    @Test
    fun `matches local or opaque MediaSession ids by normalized title`() {
        val snapshot = queue(
            currentId = "",
            currentTitle = "Everything I Wanted",
            nextId = "28512333",
        )

        assertEquals(
            "28512333",
            KuwoNextTrackBinding.align(metadata("local-song", "everything i wanted"), snapshot)
                ?.next
                ?.id,
        )
    }

    @Test
    fun `clears preview when queue current cannot be tied to MediaSession`() {
        val snapshot = queue(
            currentId = "",
            currentTitle = "Different Song",
            nextId = "28512333",
        )

        assertNull(
            KuwoNextTrackBinding.align(metadata("local-song", "everything i wanted"), snapshot),
        )
    }

    private fun metadata(id: String, title: String) = KuwoTrackMetadata(
        mediaId = id,
        title = title,
        artist = "artist",
        album = "album",
        durationMs = 180_000L,
    )

    private fun queue(
        currentId: String,
        currentTitle: String = "everything i wanted",
        nextId: String,
    ) = KuwoQueueSnapshot(
        current = KuwoTrackSnapshot(
            id = currentId,
            title = currentTitle,
            artist = "Billie Eilish",
            album = "everything i wanted",
            durationMs = 245_000L,
        ),
        next = KuwoTrackSnapshot(
            id = nextId,
            title = "Without You",
            artist = "Avicii / Sandro Cavazza",
            album = "AVĪCI (01)",
            durationMs = 181_000L,
        ),
    )
}
