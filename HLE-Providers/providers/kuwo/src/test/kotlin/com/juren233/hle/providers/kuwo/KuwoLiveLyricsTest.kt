/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

class KuwoLiveLyricsTest {
    @Test
    fun `live Kuwo response contains word timing when source supports it`() {
        val rid = System.getenv("KUWO_LIVE_WORD_TEST_ID")?.toLongOrNull()
        assumeTrue("Set KUWO_LIVE_WORD_TEST_ID to run the live LRCX test", rid != null)

        val lines = fetch(rid!!)
        assertTrue("Expected Kuwo lyric lines", lines.isNotEmpty())
        assertTrue("Expected Kuwo word timings", lines.sumOf { it.words.size } > lines.size)
    }

    @Test
    fun `live Kuwo line-only response stays usable without fabricated words`() {
        val rid = System.getenv("KUWO_LIVE_LINE_TEST_ID")?.toLongOrNull()
        assumeTrue("Set KUWO_LIVE_LINE_TEST_ID to run the live line-only test", rid != null)

        val lines = fetch(rid!!)
        assertTrue("Expected Kuwo lyric lines", lines.isNotEmpty())
        assertTrue("Expected the selected source to remain line-only", lines.all { it.words.isEmpty() })
    }

    private fun fetch(rid: Long): List<KuwoTimelineLine> {
        val query = KuwoLyricsResponseDecoder.buildRequestQuery(rid)
        val connection = (
            URI("https://newlyric.kuwo.cn/newlyric.lrc?$query")
                .toURL().openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "okhttp/3.10.0")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val response = try {
            assertTrue("Kuwo HTTP ${connection.responseCode}", connection.responseCode == 200)
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        val raw = KuwoLyricsResponseDecoder.decode(response)
        return KuwoLyricsParser.parse(raw)
    }
}
