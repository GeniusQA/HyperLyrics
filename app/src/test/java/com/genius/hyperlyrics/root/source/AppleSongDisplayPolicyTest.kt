/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.root.source

import com.genius.hyperlyrics.lyric.model.RichLyricLine
import com.genius.hyperlyrics.lyric.model.Song
import com.genius.hyperlyrics.lyric.view.SongPreprocessor
import com.genius.hyperlyrics.lyric.view.TitleSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSongDisplayPolicyTest {
    @Test
    fun `title placeholder is added only to the isolated display copy`() {
        val nativeSong = Song(
            id = "1779893139",
            name = "明弦音",
            artist = "MyGO!!!!!",
            duration = 243_920L,
            lyrics = listOf(
                RichLyricLine(
                    begin = 15_976L,
                    end = 20_000L,
                    text = "First native lyric",
                )
            ),
        )
        val displaySong = AppleSongDisplayPolicy.copyForDisplay(nativeSong)!!

        val prepared = SongPreprocessor(
            placeholder = TitleSlot.NAME_ARTIST,
            mergeOverlappingLyrics = true,
        ).prepare(displaySong)

        assertNotSame(nativeSong, displaySong)
        assertNotSame(nativeSong.lyrics?.single(), displaySong.lyrics?.last())
        assertEquals(1, nativeSong.lyrics.orEmpty().size)
        assertEquals(2, displaySong.lyrics.orEmpty().size)
        assertEquals(2, prepared.size)
        assertFalse(
            nativeSong.lyrics.orEmpty().any {
                it.metadata?.getString(SongPreprocessor.KEY_TITLE_LINE) == "true"
            }
        )
        assertTrue(
            displaySong.lyrics.orEmpty().first().metadata
                ?.getString(SongPreprocessor.KEY_TITLE_LINE) == "true"
        )
        assertEquals("明弦音 - MyGO!!!!!", displaySong.lyrics.orEmpty().first().text)
    }
}
