/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class SaltPlayerEmbeddedLyricsReaderTest {
    @Test
    fun `reads id3v2 unsynchronized lyrics`() {
        val lyrics = "[00:01.00]ID3 lyric"
        val payload = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + lyrics.toByteArray()
        val frame = "USLT".toByteArray() + bigEndian(payload.size) + byteArrayOf(0, 0) + payload
        val bytes = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncSafe(frame.size) + frame

        assertEquals(lyrics, read(bytes, "sample.mp3"))
    }

    @Test
    fun `reads flac vorbis lyrics comment`() {
        val lyrics = "[00:02.00]FLAC lyric"
        val bytes = flacWithLyrics(lyrics)

        assertEquals(lyrics, read(bytes, "sample.flac"))
    }

    @Test
    fun `flac salt thin space translations are separated after embedded tag reading`() {
        val lyrics = """
            [00:01.00]Hello world 你好世界
            [00:03.00]Number 33 第三十三号
            [00:05.00]Good night 晚安
        """.trimIndent()
        val decoded = read(flacWithLyrics(lyrics), "sample.flac")!!
        val document = SaltPlayerLrcParser.parse(decoded)!!

        assertEquals("Hello world", document.lines.first().mainText)
        assertEquals("你好世界", document.lines.first().translation)
        assertEquals("Number 33", document.lines[1].mainText)
        assertEquals("第三十三号", document.lines[1].translation)
    }

    @Test
    fun `reads apev2 lyrics item`() {
        val lyrics = "[00:03.00]APE lyric"
        val value = lyrics.toByteArray()
        val item = littleEndian(value.size) + littleEndian(0) +
            "LYRICS".toByteArray() + byteArrayOf(0) + value
        val tagSize = item.size + 32
        val footer = "APETAGEX".toByteArray() + littleEndian(2_000) +
            littleEndian(tagSize) + littleEndian(1) + littleEndian(0) + ByteArray(8)
        val bytes = ByteArray(32) + item + footer

        assertEquals(lyrics, read(bytes, "sample.ape"))
    }

    private fun read(bytes: ByteArray, fileName: String): String? {
        val path = Files.createTempFile("salt-player-lyrics", ".bin")
        return try {
            Files.write(path, bytes)
            FileInputStream(path.toFile()).use { input ->
                SaltPlayerEmbeddedLyricsReader.read(input.channel, fileName)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun flacWithLyrics(lyrics: String): ByteArray {
        val vendor = "test".toByteArray()
        val comment = "LYRICS=$lyrics".toByteArray()
        val block = littleEndian(vendor.size) + vendor + littleEndian(1) +
            littleEndian(comment.size) + comment
        val blockHeader = byteArrayOf(0x84.toByte()) + unsigned24(block.size)
        return "fLaC".toByteArray() + blockHeader + block
    }

    private fun bigEndian(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(value)
        .array()

    private fun littleEndian(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

    private fun unsigned24(value: Int): ByteArray = byteArrayOf(
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    )

    private fun syncSafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(),
        ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )
}
