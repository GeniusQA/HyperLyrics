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
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class KuwoLyricsResponseDecoderTest {
    @Test
    fun `decodes compressed xor-protected LRCX response`() {
        val expected = "[00:01.000]<800,-800>测<1600,0>试"
        val encrypted = xor(expected.toByteArray(Charset.forName("GB18030")))
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { stream ->
                stream.write(Base64.getEncoder().encode(encrypted))
            }
        }.toByteArray()
        val response = "tp=content\r\npath=test\r\n\r\n".toByteArray(StandardCharsets.US_ASCII) +
            compressed

        assertEquals(expected, KuwoLyricsResponseDecoder.decode(response))
    }

    @Test
    fun `rejects no-content response`() {
        assertNull(
            KuwoLyricsResponseDecoder.decode(
                "tp=none\r\ncand_lrc_count=0\r\n".toByteArray(StandardCharsets.US_ASCII),
            ),
        )
    }

    @Test
    fun `request query carries the official lyric request after xor round trip`() {
        val query = KuwoLyricsResponseDecoder.buildRequestQuery(81_466_699L)
        val plain = xor(Base64.getDecoder().decode(query)).toString(StandardCharsets.UTF_8)

        assertTrue(plain.contains("rid=MUSIC_81466699"))
        assertTrue(plain.contains("req=1"))
        assertTrue(plain.endsWith("lrcx=1"))
    }

    private fun xor(data: ByteArray): ByteArray {
        val key = "yeelion".toByteArray(StandardCharsets.US_ASCII)
        return ByteArray(data.size) { index ->
            (data[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
    }
}
