/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaltPlayerLrcParserTest {
    @Test
    fun `plain lrc lines end at the following line`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [00:01.00]First
            [00:03.50]Second
            """.trimIndent(),
            durationMs = 8_000L,
        )!!

        assertEquals(listOf(1_000L, 3_500L), document.lines.map { it.beginMs })
        assertEquals(listOf(3_500L, 6_500L), document.lines.map { it.endMs })
        assertEquals(listOf("First", "Second"), document.lines.map { it.mainText })
    }

    @Test
    fun `last plain lrc line uses salt default duration`() {
        val document = SaltPlayerLrcParser.parse(
            "[00:10.00]Last",
            durationMs = 0L,
        )!!

        assertEquals(10_000L, document.lines.single().beginMs)
        assertEquals(13_000L, document.lines.single().endMs)
    }

    @Test
    fun `last plain lrc line is clamped when media ends sooner`() {
        val document = SaltPlayerLrcParser.parse(
            "[00:10.00]Last",
            durationMs = 11_500L,
        )!!

        assertEquals(11_500L, document.lines.single().endMs)
    }

    @Test
    fun `enhanced lrc becomes word timing within one active line`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [00:01.00]<00:01.00>Hello <00:01.60>world
            [00:03.00]Next
            """.trimIndent(),
        )!!

        val line = document.lines.first()
        assertEquals("Hello world", line.mainText)
        assertEquals(listOf("Hello ", "world"), line.cells.map { it.text })
        assertEquals(listOf(1_000L, 1_600L), line.cells.map { it.beginMs })
        assertEquals(listOf(1_600L, 3_000L), line.cells.map { it.endMs })
        assertEquals(3_000L, line.endMs)
    }

    @Test
    fun `same timestamp plain line is kept as translation`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [00:01.00]<00:01.00>Hello
            [00:01.00]你好
            [00:02.00]Next
            """.trimIndent(),
        )!!

        val line = document.lines.first()
        assertEquals("Hello", line.mainText)
        assertEquals("你好", line.translation)
        assertEquals(2_000L, line.endMs)
    }

    @Test
    fun `salt thin space separator creates a translation at the preceding timestamp`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [00:01.00]Your ex friend's sister 你以前朋友的姐姐
            [00:03.00]2003, unbearable 2003年 无法忍受
            """.trimIndent(),
        )!!

        assertEquals("Your ex friend's sister", document.lines[0].mainText)
        assertEquals("你以前朋友的姐姐", document.lines[0].translation)
        assertEquals("2003, unbearable", document.lines[1].mainText)
        assertEquals("2003年 无法忍受", document.lines[1].translation)
    }

    @Test
    fun `salt thin space translation is excluded from enhanced lrc word cells`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [00:01.00]<00:01.00>Hello <00:01.60>world 你好世界
            [00:03.00]Next
            """.trimIndent(),
        )!!

        val line = document.lines.first()
        assertEquals("Hello world", line.mainText)
        assertEquals("你好世界", line.translation)
        assertEquals(listOf("Hello ", "world"), line.cells.map { it.text })
    }

    @Test
    fun `ordinary ascii spaces do not imply a translation`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [00:01.00]Hello world 你好世界
            [00:03.00]Number 33第三十三号
            [00:05.00]你好世界 Hello world
            """.trimIndent(),
        )!!

        assertEquals("Hello world 你好世界", document.lines[0].mainText)
        assertEquals("Number 33第三十三号", document.lines[1].mainText)
        assertEquals("你好世界 Hello world", document.lines[2].mainText)
        assertEquals(listOf(null, null, null), document.lines.map { it.translation })
    }

    @Test
    fun `multiple salt continuation segments are retained in translation order`() {
        val document = SaltPlayerLrcParser.parse(
            "[00:01.00]Main Translation one Translation two\n[00:03.00]Next",
        )!!

        assertEquals("Main", document.lines.first().mainText)
        assertEquals("Translation one\nTranslation two", document.lines.first().translation)
    }

    @Test
    fun `untimed physical continuation follows salt timestamp inheritance`() {
        val document = SaltPlayerLrcParser.parse(
            "[00:01.00]Main\nTranslation\n[00:03.00]Next",
        )!!

        assertEquals("Main", document.lines.first().mainText)
        assertEquals("Translation", document.lines.first().translation)
    }

    @Test
    fun `offset moves line and word timing together`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [offset:250]
            [00:01.00]<00:01.00>A
            [00:02.00]B
            """.trimIndent(),
        )!!

        assertEquals(1_250L, document.lines.first().beginMs)
        assertEquals(1_250L, document.lines.first().cells.single().beginMs)
        assertEquals(2_250L, document.lines.first().endMs)
    }

    @Test
    fun `returns null when no timed lyric exists`() {
        assertNull(SaltPlayerLrcParser.parse("[ar:Artist]\nUntimed text"))
    }
}
