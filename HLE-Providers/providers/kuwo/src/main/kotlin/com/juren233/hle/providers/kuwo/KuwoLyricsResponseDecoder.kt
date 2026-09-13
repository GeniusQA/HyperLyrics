/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.InflaterInputStream

internal object KuwoLyricsResponseDecoder {
    private val lyricKey = "yeelion".toByteArray(StandardCharsets.US_ASCII)
    private val gb18030 = Charset.forName("GB18030")

    fun buildRequestQuery(rid: Long): String {
        require(rid > 0L) { "rid must be positive" }
        val request =
            "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_$rid&lrcx=1"
        return Base64.getEncoder().encodeToString(xor(request.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decode(response: ByteArray): String? {
        if (!response.startsWith("tp=content".toByteArray(StandardCharsets.US_ASCII))) return null
        val payloadOffset = response.indexOf(HEADER_SEPARATOR)
            .takeIf { it >= 0 }
            ?.plus(HEADER_SEPARATOR.size)
            ?: return null
        val inflated = runCatching {
            InflaterInputStream(
                ByteArrayInputStream(response, payloadOffset, response.size - payloadOffset),
            ).use { it.readBytes() }
        }.getOrNull() ?: return null
        val encrypted = runCatching {
            Base64.getMimeDecoder().decode(
                inflated.toString(StandardCharsets.US_ASCII).trim(),
            )
        }.getOrNull() ?: return null
        return xor(encrypted).toString(gb18030).takeIf(String::isNotBlank)
    }

    private fun xor(data: ByteArray): ByteArray = ByteArray(data.size) { index ->
        (data[index].toInt() xor lyricKey[index % lyricKey.size].toInt()).toByte()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        for (start in 0..size - needle.size) {
            if (needle.indices.all { offset -> this[start + offset] == needle[offset] }) return start
        }
        return -1
    }

    private val HEADER_SEPARATOR = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
}
