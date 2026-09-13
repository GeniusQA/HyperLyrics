/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouCandidateSelectorTest {
    @Test
    fun `does not reload lyrics when only album metadata is completed`() {
        val previous = KuGouTrackMetadata("MUSIC_123", "至少还有你", "林忆莲", null, 274_000L)
        val next = previous.copy(album = "林忆莲's")

        assertFalse(KuGouTrackUpdatePolicy.shouldReloadLyrics(previous, next))
    }

    @Test
    fun `reloads lyrics when track identity changes`() {
        val previous = KuGouTrackMetadata("MUSIC_123", "至少还有你", "林忆莲", null, 274_000L)
        val next = KuGouTrackMetadata("MUSIC_456", "我知道", "BY2", "Twins", 250_000L)

        assertTrue(KuGouTrackUpdatePolicy.shouldReloadLyrics(previous, next))
    }

    @Test
    fun `prefers matching title artist and duration`() {
        val track = KuGouTrackMetadata(null, "晴天", "周杰伦", "叶惠美", 269_000L)
        val selected = KuGouCandidateSelector.rank(
            track,
            listOf(
                candidate("1", "晴天", "其他歌手", 269_000L),
                candidate("34988004", "晴天", "周杰伦", 269_000L),
            ),
        )

        assertEquals("34988004", selected.firstOrNull()?.downloadId)
    }

    @Test
    fun `rejects exact title from a different artist`() {
        val track = KuGouTrackMetadata(null, "Without You", "Avicii", null, 181_000L)
        val selected = KuGouCandidateSelector.rank(
            track,
            listOf(candidate("7", "Without You", "Other Artist", 181_000L)),
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `uses first server candidate when media id is a direct Kugou hash`() {
        val track = KuGouTrackMetadata(
            "0123456789abcdef0123456789abcdef",
            null,
            null,
            null,
            0L,
        )
        val selected = KuGouCandidateSelector.rank(
            track,
            listOf(candidate("11", null, null, 0L)),
        )

        assertEquals("11", selected.firstOrNull()?.downloadId)
    }

    @Test
    fun `ranks every qualifying candidate best score first`() {
        val track = KuGouTrackMetadata(null, "晴天", "周杰伦", null, 269_000L)
        val ranked = KuGouCandidateSelector.rank(
            track,
            listOf(
                candidate("a", "晴天", "周杰伦", 269_000L),
                candidate("b", "晴天", "周杰伦", 272_000L),
                candidate("c", "晴天", "周杰伦", 278_000L),
            ),
        )

        // 同为完整标题+歌手：时长越近加分越高
        assertEquals(listOf("a", "b", "c"), ranked.map { it.downloadId })
    }

    @Test
    fun `keeps server order for equal scores`() {
        val track = KuGouTrackMetadata(null, "晴天", "周杰伦", null, 269_000L)
        val ranked = KuGouCandidateSelector.rank(
            track,
            listOf(
                candidate("first", "晴天", "周杰伦", 269_000L),
                candidate("second", "晴天", "周杰伦", 269_000L),
            ),
        )

        assertEquals(listOf("first", "second"), ranked.map { it.downloadId })
    }

    @Test
    fun `drops non qualifying candidates from the ranked list`() {
        val track = KuGouTrackMetadata(null, "晴天", "周杰伦", null, 269_000L)
        val ranked = KuGouCandidateSelector.rank(
            track,
            listOf(
                candidate("match", "晴天", "周杰伦", 269_000L),
                candidate("mismatch", "完全不同", "别的歌手", 120_000L),
            ),
        )

        assertEquals(listOf("match"), ranked.map { it.downloadId })
    }

    private fun candidate(
        id: String,
        title: String?,
        artist: String?,
        durationMs: Long,
    ) = KuGouSearchCandidate(
        downloadId = id,
        accessKey = "access-$id",
        contentType = 0,
        title = title,
        artist = artist,
        durationMs = durationMs,
        serverScore = 60,
    )
}
