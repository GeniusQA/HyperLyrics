/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyLyricsPayloadTest {
    @Test
    fun `extracts mapped color lyrics with URI syllables and alternatives`() {
        val payload = requireNotNull(
            SpotifyLyricsPayloadExtractor.extract(
                trackKey = "spotify:track:1234567890123456789012",
                result = FakeResult(
                    FakeLyrics(
                        a = listOf(
                            FakeLine(1_000L, "Hello world", listOf(FakeSyllable(1_000, 6), FakeSyllable(1_700, 5))),
                            FakeLine(3_000L, "Again", emptyList()),
                        ),
                        b = 3,
                        c = listOf(FakeTranslation("zh", listOf("你好，世界", "再一次"))),
                        d = "en",
                    ),
                ),
            ),
        )

        assertEquals("spotify:track:1234567890123456789012", payload.trackUri)
        assertEquals(3, payload.syncType)
        assertEquals("en", payload.language)
        assertEquals(listOf("Hello world", "Again"), payload.lines.map { it.text })
        assertEquals(listOf("你好，世界", "再一次"), payload.translations.single().lines)
    }

    @Test
    fun `binds the request URI directly to its asynchronous success value`() {
        val lyrics = FakeLyrics(
            a = listOf(FakeLine(1_000L, "Hello", emptyList())),
            b = 2,
            c = emptyList(),
            d = "en",
        )

        val payload = requireNotNull(
            SpotifyLyricsPayloadExtractor.extract(
                trackKey = "spotify:track:1234567890123456789012",
                result = FakeResult(lyrics),
            ),
        )

        assertEquals("spotify:track:1234567890123456789012", payload.trackUri)
        assertEquals("Hello", payload.lines.single().text)
    }

    @Test
    fun `maps syllable timing by line index and ignores native translations`() {
        val payload = SpotifyLyricsPayload(
            trackUri = "spotify:track:1234567890123456789012",
            syncType = 3,
            language = "en",
            lines = listOf(
                SpotifyApiLyricLine(
                    startMs = 1_000L,
                    text = "Hello world",
                    syllables = listOf(SpotifySyllable(1_000L, 6), SpotifySyllable(1_700L, 5)),
                ),
                SpotifyApiLyricLine(3_000L, "Again", emptyList()),
            ),
            translations = listOf(SpotifyTranslation("zh", listOf("你好，世界", "再一次"))),
        )

        val lines = SpotifyLyricsTimelineMapper.map(payload, durationMs = 6_000L)

        assertEquals(3_000L, lines[0].end)
        assertEquals(listOf("Hello ", "world"), lines[0].words.map { it.text })
        assertEquals(listOf(1_000L, 1_700L), lines[0].words.map { it.begin })
        assertEquals(listOf(1_700L, 3_000L), lines[0].words.map { it.end })
        assertEquals(6_000L, lines[1].end)
    }

    private class FakeResult(val a: FakeLyrics)
    private class FakeLyrics(
        val a: List<FakeLine>,
        val b: Int,
        val c: List<FakeTranslation>,
        val d: String,
    )
    private class FakeLine(val a: Long, val b: String, val c: List<FakeSyllable>)
    private class FakeSyllable(val a: Int, val b: Int)
    private class FakeTranslation(val a: String, val b: List<String>, val c: Boolean = false)
}
