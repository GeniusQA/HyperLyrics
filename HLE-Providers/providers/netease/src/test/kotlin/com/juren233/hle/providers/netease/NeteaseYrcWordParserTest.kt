/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseYrcWordParserTest {
    @Test
    fun `preserves Latin spaces between timed words`() {
        val words = NeteaseYrcWordParser.parse(
            "(13310,240,0)I (13550,310,0)love (13860,270,0)you (14130,500,0)so",
        )

        assertEquals(listOf("I ", "love ", "you ", "so"), words.map { it.text })
        assertEquals("I love you so", words.joinToString("") { it.text })
    }

    @Test
    fun `preserves whitespace-only timed segments`() {
        val words = NeteaseYrcWordParser.parse(
            "(1000,200,0)hello(1200,100,0) (1300,200,0)world",
        )

        assertEquals(listOf("hello", " ", "world"), words.map { it.text })
        assertEquals("hello world", words.joinToString("") { it.text })
    }

    @Test
    fun `preserves lyric parentheses instead of treating them as delimiters`() {
        val words = NeteaseYrcWordParser.parse(
            "(1000,300,0)(Oh) (1300,300,0)yeah",
        )

        assertEquals(listOf("(Oh) ", "yeah"), words.map { it.text })
    }

    @Test
    fun `keeps timing values unchanged`() {
        val word = NeteaseYrcWordParser.parse("(1234,567,0)test").single()

        assertEquals(1234L, word.begin)
        assertEquals(567L, word.duration)
    }
}
