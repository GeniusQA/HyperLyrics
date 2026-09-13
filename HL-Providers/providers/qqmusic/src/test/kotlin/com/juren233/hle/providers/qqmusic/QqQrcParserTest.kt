/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import com.juren233.hle.providers.qqmusic.qrc.QqQrcDecrypter
import com.juren233.hle.providers.qqmusic.qrc.QqQrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

class QqQrcParserTest {
    @Test
    fun `parses QRC word timings and preserves spaces`() {
        val lines = QqQrcParser.parse(
            """<LyricContent LyricContent="[ti:Demo][0,1200]H(0,300)i(300,300) (600,200)!(800,400)]"/>""",
        )

        assertEquals(1, lines.size)
        assertEquals("Hi !", lines.single().text)
        assertEquals(4, lines.single().words.size)
        assertEquals(0L, lines.single().words[0].begin)
        assertEquals(300L, lines.single().words[0].duration)
        assertEquals(" ", lines.single().words[2].text)
        assertTrue(lines.single().end > lines.single().begin)
    }

    @Test
    fun `plain LRC is not mistaken for QRC`() {
        assertTrue(QqQrcParser.parse("[00:01.00]hello").isEmpty())
    }

    @Test
    fun `live QQ response contains word timings and translation when requested`() {
        val id = System.getenv("QQMUSIC_LIVE_TEST_ID")
        assumeTrue("Set QQMUSIC_LIVE_TEST_ID to run the live QRC test", !id.isNullOrBlank())

        val connection = (URI("https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg")
            .toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://y.qq.com/")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val raw = try {
            val body = "version=15&miniversion=100&lrctype=4&musicid=$id"
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val encryptedLyric = cdata(raw, "content")
        val translation = cdata(raw, "contentts")
        val decodedLyric = QqQrcDecrypter.decode(encryptedLyric)
        assertNotNull(decodedLyric)
        val lines = QqQrcParser.parse(decodedLyric)
        assertTrue("Expected QRC lines", lines.isNotEmpty())
        assertTrue("Expected QRC word timings", lines.sumOf { it.words.size } > lines.size)
        assertTrue("Expected translated LRC", !translation.isNullOrBlank())
        assertTrue("Expected timestamped translation", Regex("\\[\\d{1,3}[:.]\\d{2}").containsMatchIn(translation!!))
    }

    private fun cdata(raw: String, name: String): String? {
        val pattern = Regex("<$name(?:\\s|>)[^>]*>.*?<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
        return pattern.find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
    }
}
