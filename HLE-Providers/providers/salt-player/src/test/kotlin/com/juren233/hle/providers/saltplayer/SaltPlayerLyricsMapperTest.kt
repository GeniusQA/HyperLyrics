/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaltPlayerLyricsMapperTest {
    @Test
    fun `maps original main translation and word timing into separate lyric tracks`() {
        val song = SaltPlayerLyricsMapper.map(
            document = SaltPlayerLyricsDocument(
                lines = listOf(
                    SaltPlayerLyricsLine(
                        beginMs = 1_000L,
                        endMs = 3_000L,
                        mainText = "Hello world",
                        translation = "你好，世界",
                        cells = listOf(
                            SaltPlayerLyricsCell(1_000L, 1_800L, "Hello"),
                            SaltPlayerLyricsCell(1_800L, 3_000L, " world"),
                        ),
                    ),
                ),
            ),
            metadata = SaltPlayerTrackMetadata(
                id = "salt-1",
                title = "Demo",
                artist = "Artist",
                durationMs = 5_000L,
            ),
        )

        assertEquals("salt-1", song.id)
        assertEquals("Demo", song.name)
        assertEquals("Artist", song.artist)
        assertEquals(5_000L, song.duration)
        val line = song.lyrics!!.single()
        assertEquals(1_000L, line.begin)
        assertEquals(3_000L, line.end)
        assertEquals("Hello world", line.text)
        assertEquals("你好，世界", line.translation)
        assertEquals(listOf("Hello", " world"), line.words!!.map { it.text })
        assertEquals(listOf(1_000L, 1_800L), line.words!!.map { it.begin })
        assertEquals(listOf(1_800L, 3_000L), line.words!!.map { it.end })
    }

    @Test
    fun `uses the whole line timing when no word cells are available`() {
        val song = SaltPlayerLyricsMapper.map(
            SaltPlayerLyricsDocument(
                listOf(
                    SaltPlayerLyricsLine(
                        beginMs = 4_000L,
                        endMs = 7_500L,
                        mainText = "Plain LRC line",
                        translation = null,
                        cells = emptyList(),
                    ),
                ),
            ),
            SaltPlayerTrackMetadata(),
        )

        val line = song.lyrics!!.single()
        val word = line.words!!.single()
        assertEquals(4_000L, word.begin)
        assertEquals(7_500L, word.end)
        assertEquals(3_500L, word.duration)
        assertEquals("Plain LRC line", word.text)
        assertNull(line.translation)
        assertEquals(7_500L, song.duration)
    }

    @Test
    fun `does not duplicate a translation identical to the main lyric`() {
        val song = SaltPlayerLyricsMapper.map(
            SaltPlayerLyricsDocument(
                listOf(
                    SaltPlayerLyricsLine(
                        beginMs = 0L,
                        endMs = 1_000L,
                        mainText = "相同",
                        translation = "相同",
                        cells = emptyList(),
                    ),
                ),
            ),
            SaltPlayerTrackMetadata(),
        )

        assertNull(song.lyrics!!.single().translation)
    }

    @Test
    fun `falls back to whole line timing when word cells are outside the line`() {
        val song = SaltPlayerLyricsMapper.map(
            SaltPlayerLyricsDocument(
                listOf(
                    SaltPlayerLyricsLine(
                        beginMs = 2_000L,
                        endMs = 4_000L,
                        mainText = "Fallback",
                        translation = null,
                        cells = listOf(
                            SaltPlayerLyricsCell(0L, 1_000L, "old"),
                            SaltPlayerLyricsCell(4_000L, 4_000L, "instant"),
                        ),
                    ),
                ),
            ),
            SaltPlayerTrackMetadata(),
        )

        val words = song.lyrics!!.single().words!!
        assertEquals(listOf("Fallback"), words.map { it.text })
        assertEquals(2_000L, words.single().begin)
        assertEquals(4_000L, words.single().end)
    }
}
