/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KuwoLyricsParserTest {
    @Test
    fun `decodes Kuwo LRCX word boundaries and preserves spaces`() {
        val line = KuwoLyricsParser.parse(
            """
            [kuwo:026]
            [00:20.792]<840,-840>I <1572,108>got <4532,-1388>everything <5552,3512>I <9440,1664>wanted
            """.trimIndent(),
        ).single()

        assertEquals("I got everything I wanted", line.text)
        assertEquals(listOf("I ", "got ", "everything ", "I ", "wanted"), line.words.map { it.text })
        assertEquals(20_792L, line.words[0].begin)
        assertEquals(21_212L, line.words[0].end)
        assertEquals(21_212L, line.words[1].begin)
        assertEquals(21_578L, line.words[1].end)
        assertEquals(23_058L, line.words[3].begin)
        assertEquals(23_058L, line.words[2].end)
        assertEquals(25_512L, line.words.last().end)
    }

    @Test
    fun `uses the per song Kuwo timing scale from the LRCX header`() {
        val line = KuwoLyricsParser.parse(
            """
            [kuwo:013]
            [00:03.661]<202,-202>I <408,-4>was <784,32>supposed
            """.trimIndent(),
        ).single()

        assertEquals(3_661L, line.words[0].begin)
        assertEquals(3_863L, line.words[0].end)
        assertEquals(3_863L, line.words[1].begin)
        assertEquals(4_069L, line.words[1].end)
        assertEquals(4_069L, line.words[2].begin)
        assertEquals(4_445L, line.words[2].end)
    }

    @Test
    fun `defaults to the Kuwo one to one timing scale when the header is absent`() {
        val line = KuwoLyricsParser.parse(
            "[00:03.661]<202,-202>I <408,-4>was",
        ).single()

        assertEquals(3_661L, line.words[0].begin)
        assertEquals(3_863L, line.words[0].end)
        assertEquals(3_863L, line.words[1].begin)
        assertEquals(4_069L, line.words[1].end)
    }

    @Test
    fun `attaches zero-timed LRCX translation to preceding word line`() {
        val lines = KuwoLyricsParser.parse(
            """
            [00:17.681]<804,-804>I <1488,120>had <2192,784>a <6932,-2548>dream
            [00:20.792]<0,0>我<0,0>做<0,0>了<0,0>个<0,0>梦
            [00:20.792]<840,-840>I <1572,108>got <4532,-1388>everything <5552,3512>I <9440,1664>wanted
            """.trimIndent(),
        )

        assertEquals(2, lines.size)
        assertEquals("我做了个梦", lines[0].translation)
        assertNull(lines[1].translation)
        assertTrue(lines.all { it.words.isNotEmpty() })
    }

    @Test
    fun `separates translation from line-only LRC using Kuwo ordering`() {
        val lines = KuwoLyricsParser.parse(
            """
            [00:18.14]You said that we would always be
            [00:21.88]你说过 我们会永远相知相守
            [00:21.88]Without you I feel lost at sea
            [00:25.17]没有你 我感觉自己在汪洋大海迷失
            [00:25.17]Through the darkness you'd hide with me
            """.trimIndent(),
        )

        assertEquals(3, lines.size)
        assertEquals("You said that we would always be", lines[0].text)
        assertEquals("你说过 我们会永远相知相守", lines[0].translation)
        assertEquals("Without you I feel lost at sea", lines[1].text)
        assertEquals("没有你 我感觉自己在汪洋大海迷失", lines[1].translation)
        assertTrue(lines.all { it.words.isEmpty() })
    }

    @Test
    fun `ordinary LRC remains usable when no translation is present`() {
        val lines = KuwoLyricsParser.parse(
            """
            [00:01.00]first
            [00:03.50]second
            """.trimIndent(),
        )

        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].begin)
        assertEquals(3_500L, lines[0].end)
        assertEquals("second", lines[1].text)
    }
}
