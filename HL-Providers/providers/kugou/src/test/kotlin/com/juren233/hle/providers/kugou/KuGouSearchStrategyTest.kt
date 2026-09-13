/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouSearchStrategyTest {
    @Test
    fun `keeps variants empty when title has no bracketed segment`() {
        val track = KuGouTrackMetadata(null, "晴天", "周杰伦", null, 269_000L)

        assertTrue(KuGouKeywordVariants.fallbackKeywords(track).isEmpty())
    }

    @Test
    fun `strips the trailing bilingual segment and keeps the artist prefix`() {
        val track = KuGouTrackMetadata(null, "사랑은 (爱情)", "IU", null, 240_000L)

        assertEquals(listOf("IU - 사랑은"), KuGouKeywordVariants.fallbackKeywords(track))
    }

    @Test
    fun `strips fullwidth brackets`() {
        val track = KuGouTrackMetadata(null, "歌曲（现场版）", null, null, 0L)

        assertEquals(listOf("歌曲"), KuGouKeywordVariants.fallbackKeywords(track))
    }

    @Test
    fun `strips leading square brackets`() {
        val track = KuGouTrackMetadata(null, "[Live] Song", null, null, 0L)

        assertEquals(listOf("Song"), KuGouKeywordVariants.fallbackKeywords(track))
    }

    @Test
    fun `removes multiple segments cumulatively from the right`() {
        val track = KuGouTrackMetadata(null, "A (B) C (D)", "X", null, 0L)

        assertEquals(
            listOf("X - A (B) C", "X - A C"),
            KuGouKeywordVariants.fallbackKeywords(track),
        )
    }

    @Test
    fun `caps variants at three`() {
        val track = KuGouTrackMetadata(null, "A (1) (2) (3) (4)", null, null, 0L)

        assertEquals(
            listOf("A (1) (2) (3)", "A (1) (2)", "A (1)"),
            KuGouKeywordVariants.fallbackKeywords(track),
        )
    }

    @Test
    fun `keeps no variant when the whole title is bracketed`() {
        val track = KuGouTrackMetadata(null, "(Live)", null, null, 0L)

        assertTrue(KuGouKeywordVariants.fallbackKeywords(track).isEmpty())
    }

    @Test
    fun `full keyword hit does not issue fallback requests`() {
        val track = KuGouTrackMetadata(null, "歌曲 (翻译)", "歌手", null, 240_000L)
        val requested = mutableListOf<String>()
        val selected = KuGouSearchStrategy.rank(track) { keyword ->
            requested += keyword
            listOf(candidate("1", "歌曲 (翻译)", "歌手", 240_000L))
        }

        assertEquals("1", selected.firstOrNull()?.downloadId)
        assertEquals(listOf("歌手 - 歌曲 (翻译)"), requested)
    }

    @Test
    fun `returns the full ranked list from the first qualifying keyword`() {
        val track = KuGouTrackMetadata(null, "歌曲 (翻译)", "歌手", null, 240_000L)
        val requested = mutableListOf<String>()
        val ranked = KuGouSearchStrategy.rank(track) { keyword ->
            requested += keyword
            listOf(
                candidate("2", "歌曲", "歌手", 240_000L),
                candidate("1", "歌曲 (翻译)", "歌手", 240_000L),
            )
        }

        assertEquals(listOf("1", "2"), ranked.map { it.downloadId })
        assertEquals(listOf("歌手 - 歌曲 (翻译)"), requested)
    }

    @Test
    fun `falls back to the bracket-stripped keyword when full keyword has no candidates`() {
        val track = KuGouTrackMetadata(null, "歌曲 (翻译)", "歌手", null, 240_000L)
        val requested = mutableListOf<String>()
        val selected = KuGouSearchStrategy.rank(track) { keyword ->
            requested += keyword
            if (keyword == "歌手 - 歌曲") {
                listOf(candidate("7", "歌曲 (翻译)", "歌手", 240_000L))
            } else {
                emptyList()
            }
        }

        assertEquals("7", selected.firstOrNull()?.downloadId)
        assertEquals(listOf("歌手 - 歌曲 (翻译)", "歌手 - 歌曲"), requested)
    }

    @Test
    fun `rejects candidates that do not match the full original metadata`() {
        val track = KuGouTrackMetadata(null, "歌曲 (翻译)", "歌手", null, 240_000L)
        val requested = mutableListOf<String>()
        val selected = KuGouSearchStrategy.rank(track) { keyword ->
            requested += keyword
            listOf(candidate("9", "完全不同的歌", "别的歌手", 120_000L))
        }

        assertTrue(selected.isEmpty())
        assertEquals(2, requested.size)
    }

    @Test
    fun `returns empty after every variant misses`() {
        val track = KuGouTrackMetadata(null, "歌曲 (翻译)", "歌手", null, 240_000L)
        val requested = mutableListOf<String>()

        assertTrue(
            KuGouSearchStrategy.rank(track) { keyword ->
                requested += keyword
                emptyList()
            }.isEmpty(),
        )
        assertEquals(listOf("歌手 - 歌曲 (翻译)", "歌手 - 歌曲"), requested)
    }

    @Test
    fun `a search error aborts the ladder instead of retrying`() {
        val track = KuGouTrackMetadata(null, "歌曲 (翻译)", "歌手", null, 240_000L)
        val requested = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            KuGouSearchStrategy.rank(track) { keyword ->
                requested += keyword
                if (requested.size == 1) {
                    emptyList()
                } else {
                    error("酷狗歌词搜索失败: errcode=20007")
                }
            }
        }
        assertEquals(2, requested.size)
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
