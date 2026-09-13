/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.min

/** Reads raw embedded lyric text without calling any Salt Player class. */
internal object SaltPlayerEmbeddedLyricsReader {
    fun read(channel: FileChannel, fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val readers = when (extension) {
            "mp3" -> listOf(::readId3Lyrics, ::readLyrics3)
            "flac" -> listOf(::readFlacLyrics)
            "ogg", "oga", "opus" -> listOf(::readOggLyrics)
            "m4a", "mp4", "m4b", "aac" -> listOf(::readMp4Lyrics)
            "ape", "wv", "mpc" -> listOf(::readApeLyrics)
            "wav" -> listOf(::readWaveLyrics, ::readId3Lyrics)
            else -> listOf(
                ::readId3Lyrics,
                ::readFlacLyrics,
                ::readOggLyrics,
                ::readMp4Lyrics,
                ::readApeLyrics,
                ::readLyrics3,
            )
        }
        return readers.firstNotNullOfOrNull { reader ->
            runCatching { reader(channel) }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
        }
    }

    private fun readId3Lyrics(channel: FileChannel): String? {
        val header = readRange(channel, 0L, 10) ?: return null
        if (!header.copyOfRange(0, 3).contentEquals(ID3_MAGIC)) return null
        val version = header[3].toInt() and 0xff
        if (version !in 2..4) return null
        val tagSize = syncSafeInt(header, 6).coerceAtMost(MAX_TAG_BYTES)
        val body = readRange(channel, 10L, tagSize) ?: return null
        val tag = if ((header[5].toInt() and 0x80) != 0) removeUnsynchronization(body) else body
        var position = when {
            version == 3 && (header[5].toInt() and 0x40) != 0 && tag.size >= 4 ->
                4 + bigEndianInt(tag, 0).coerceAtLeast(0)
            version == 4 && (header[5].toInt() and 0x40) != 0 && tag.size >= 4 ->
                syncSafeInt(tag, 0).coerceAtLeast(0)
            else -> 0
        }
        while (position < tag.size) {
            val frameHeaderSize = if (version == 2) 6 else 10
            if (position + frameHeaderSize > tag.size) break
            val idLength = if (version == 2) 3 else 4
            val id = ascii(tag, position, idLength)
            if (id.all { it == '\u0000' }) break
            val frameSize = if (version == 2) {
                unsigned24(tag, position + 3)
            } else if (version == 4) {
                syncSafeInt(tag, position + 4)
            } else {
                bigEndianInt(tag, position + 4)
            }
            if (frameSize <= 0 || position + frameHeaderSize + frameSize > tag.size) break
            val payload = tag.copyOfRange(
                position + frameHeaderSize,
                position + frameHeaderSize + frameSize,
            )
            val decoded = when (id) {
                "USLT", "ULT" -> decodeUnsynchronizedLyrics(payload)
                "TXXX", "TXX" -> decodeUserTextLyrics(payload)
                else -> null
            }
            if (!decoded.isNullOrBlank()) return decoded
            position += frameHeaderSize + frameSize
        }
        return null
    }

    private fun readLyrics3(channel: FileChannel): String? {
        val tail = readTail(channel, MAX_TAIL_BYTES) ?: return null
        val marker = tail.lastIndexOf(LYRICS3_END)
        if (marker < 6) return null
        val size = ascii(tail, marker - 6, 6).toIntOrNull() ?: return null
        val start = marker - 6 - size
        if (start < 0) return null
        var position = start
        while (position + 8 <= marker - 6) {
            val id = ascii(tail, position, 3)
            val fieldSize = ascii(tail, position + 3, 5).toIntOrNull() ?: break
            val valueStart = position + 8
            val valueEnd = valueStart + fieldSize
            if (valueEnd > marker - 6) break
            if (id == "LYR") return decodeBestEffort(tail.copyOfRange(valueStart, valueEnd))
            position = valueEnd
        }
        return null
    }

    private fun readFlacLyrics(channel: FileChannel): String? {
        val prefix = readRange(channel, 0L, min(channel.size(), MAX_HEAD_BYTES.toLong()).toInt())
            ?: return null
        if (prefix.size < 4 || !prefix.copyOfRange(0, 4).contentEquals(FLAC_MAGIC)) return null
        var position = 4
        while (position + 4 <= prefix.size) {
            val header = prefix[position].toInt() and 0xff
            val type = header and 0x7f
            val length = unsigned24(prefix, position + 1)
            val start = position + 4
            val end = start + length
            if (end > prefix.size) break
            if (type == 4) return parseVorbisComments(prefix, start)
            position = end
            if ((header and 0x80) != 0) break
        }
        return null
    }

    private fun readOggLyrics(channel: FileChannel): String? {
        val prefix = readRange(channel, 0L, min(channel.size(), MAX_HEAD_BYTES.toLong()).toInt())
            ?: return null
        var position = 0
        val packet = ArrayList<Byte>()
        while (position + 27 <= prefix.size) {
            if (!prefix.copyOfRange(position, position + 4).contentEquals(OGG_MAGIC)) break
            val segmentCount = prefix[position + 26].toInt() and 0xff
            if (position + 27 + segmentCount > prefix.size) break
            val dataStart = position + 27 + segmentCount
            var dataPosition = dataStart
            for (segmentIndex in 0 until segmentCount) {
                val segmentSize = prefix[position + 27 + segmentIndex].toInt() and 0xff
                if (dataPosition + segmentSize > prefix.size) return null
                for (index in dataPosition until dataPosition + segmentSize) packet += prefix[index]
                dataPosition += segmentSize
                if (segmentSize < 255) {
                    val bytes = packet.toByteArray()
                    when {
                        bytes.startsWith(OPUS_TAGS) ->
                            return parseVorbisComments(bytes, OPUS_TAGS.size)
                        bytes.startsWith(VORBIS_COMMENT_MAGIC) ->
                            return parseVorbisComments(bytes, VORBIS_COMMENT_MAGIC.size)
                    }
                    packet.clear()
                }
            }
            position = dataPosition
        }
        return null
    }

    private fun readMp4Lyrics(channel: FileChannel): String? {
        val windows = readHeadAndTail(channel, MAX_MP4_SCAN_BYTES)
        windows.forEach { bytes ->
            var position = 4
            while (position + 4 <= bytes.size) {
                if (bytes[position] == 0xA9.toByte() &&
                    bytes[position + 1] == 'l'.code.toByte() &&
                    bytes[position + 2] == 'y'.code.toByte() &&
                    bytes[position + 3] == 'r'.code.toByte()
                ) {
                    val atomStart = position - 4
                    val atomSize = bigEndianInt(bytes, atomStart)
                    if (atomSize >= 16 && atomStart + atomSize <= bytes.size) {
                        val dataType = byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte())
                        val dataPosition = bytes.indexOf(dataType, position + 4, atomStart + atomSize)
                        if (dataPosition >= 4) {
                            val dataSize = bigEndianInt(bytes, dataPosition - 4)
                            val textStart = dataPosition + 12
                            val textEnd = (dataPosition - 4 + dataSize).coerceAtMost(atomStart + atomSize)
                            if (textEnd > textStart) {
                                return decodeBestEffort(bytes.copyOfRange(textStart, textEnd))
                            }
                        }
                    }
                }
                position++
            }
        }
        return null
    }

    private fun readApeLyrics(channel: FileChannel): String? {
        if (channel.size() < 32L) return null
        val footer = readRange(channel, channel.size() - 32L, 32) ?: return null
        if (!footer.copyOfRange(0, 8).contentEquals(APE_MAGIC)) return null
        val tagSize = littleEndianInt(footer, 12)
        val itemCount = littleEndianInt(footer, 16)
        if (tagSize !in 32..MAX_TAG_BYTES || itemCount !in 1..MAX_APE_ITEMS) return null
        val tag = readRange(channel, channel.size() - tagSize, tagSize - 32) ?: return null
        var position = if (tag.startsWith(APE_MAGIC)) 32 else 0
        repeat(itemCount) {
            if (position + 8 > tag.size) return null
            val valueSize = littleEndianInt(tag, position)
            position += 8
            val keyEnd = tag.indexOf(0, position)
            if (keyEnd < 0) return null
            val key = ascii(tag, position, keyEnd - position).uppercase()
            position = keyEnd + 1
            if (valueSize < 0 || position + valueSize > tag.size) return null
            if (key in LYRIC_KEYS) return decodeBestEffort(tag.copyOfRange(position, position + valueSize))
            position += valueSize
        }
        return null
    }

    private fun readWaveLyrics(channel: FileChannel): String? {
        val prefix = readRange(channel, 0L, min(channel.size(), MAX_HEAD_BYTES.toLong()).toInt())
            ?: return null
        if (prefix.size < 12 || ascii(prefix, 0, 4) != "RIFF" || ascii(prefix, 8, 4) != "WAVE") {
            return null
        }
        var position = 12
        while (position + 8 <= prefix.size) {
            val id = ascii(prefix, position, 4)
            val size = littleEndianInt(prefix, position + 4)
            val start = position + 8
            val end = start + size
            if (size < 0 || end > prefix.size) break
            if (id.equals("id3 ", ignoreCase = true)) {
                return readId3TagBytes(prefix.copyOfRange(start, end))
            }
            position = end + (size and 1)
        }
        return null
    }

    private fun readId3TagBytes(bytes: ByteArray): String? {
        if (bytes.size < 10 || !bytes.copyOfRange(0, 3).contentEquals(ID3_MAGIC)) return null
        val version = bytes[3].toInt() and 0xff
        val tagSize = syncSafeInt(bytes, 6).coerceAtMost(bytes.size - 10)
        val body = bytes.copyOfRange(10, 10 + tagSize)
        var position = 0
        while (position + 10 <= body.size) {
            val id = ascii(body, position, 4)
            val size = if (version == 4) syncSafeInt(body, position + 4) else bigEndianInt(body, position + 4)
            if (size <= 0 || position + 10 + size > body.size) break
            val payload = body.copyOfRange(position + 10, position + 10 + size)
            if (id == "USLT") return decodeUnsynchronizedLyrics(payload)
            position += 10 + size
        }
        return null
    }

    private fun decodeUnsynchronizedLyrics(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 0xff
        var position = 4
        position = skipTerminatedText(payload, position, encoding)
        if (position >= payload.size) return null
        return decodeText(payload.copyOfRange(position, payload.size), encoding)
    }

    private fun decodeUserTextLyrics(payload: ByteArray): String? {
        if (payload.size < 2) return null
        val encoding = payload[0].toInt() and 0xff
        val descriptionEnd = findTextTerminator(payload, 1, encoding)
        if (descriptionEnd < 0) return null
        val description = decodeText(payload.copyOfRange(1, descriptionEnd), encoding)
            ?.trim()?.uppercase() ?: return null
        val valueStart = descriptionEnd + if (encoding == 1 || encoding == 2) 2 else 1
        if (description !in LYRIC_KEYS || valueStart >= payload.size) return null
        return decodeText(payload.copyOfRange(valueStart, payload.size), encoding)
    }

    private fun parseVorbisComments(bytes: ByteArray, start: Int): String? {
        var position = start
        if (position + 4 > bytes.size) return null
        val vendorLength = littleEndianInt(bytes, position)
        position += 4 + vendorLength
        if (vendorLength < 0 || position + 4 > bytes.size) return null
        val commentCount = littleEndianInt(bytes, position)
        position += 4
        if (commentCount !in 0..MAX_VORBIS_COMMENTS) return null
        repeat(commentCount) {
            if (position + 4 > bytes.size) return null
            val length = littleEndianInt(bytes, position)
            position += 4
            if (length < 0 || position + length > bytes.size) return null
            val comment = String(bytes, position, length, StandardCharsets.UTF_8)
            position += length
            val separator = comment.indexOf('=')
            if (separator > 0) {
                val key = comment.substring(0, separator).uppercase()
                if (key in LYRIC_KEYS) return comment.substring(separator + 1)
            }
        }
        return null
    }

    private fun decodeText(bytes: ByteArray, encoding: Int): String? = when (encoding) {
        0 -> String(bytes, StandardCharsets.ISO_8859_1)
        1 -> String(bytes, StandardCharsets.UTF_16)
        2 -> String(bytes, StandardCharsets.UTF_16BE)
        3 -> String(bytes, StandardCharsets.UTF_8)
        else -> null
    }?.trimEnd('\u0000')

    internal fun decodeBestEffort(bytes: ByteArray): String? {
        val withoutBom = when {
            bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) ->
                bytes.copyOfRange(3, bytes.size) to StandardCharsets.UTF_8
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                bytes.copyOfRange(2, bytes.size) to StandardCharsets.UTF_16LE
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                bytes.copyOfRange(2, bytes.size) to StandardCharsets.UTF_16BE
            else -> bytes to null
        }
        withoutBom.second?.let { return String(withoutBom.first, it).trimEnd('\u0000') }
        listOf(StandardCharsets.UTF_8, Charset.forName("GB18030"), StandardCharsets.UTF_16LE)
            .forEach { charset ->
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
                    .getOrNull()?.trimEnd('\u0000')?.takeIf(String::isNotBlank)?.let { return it }
            }
        return null
    }

    private fun skipTerminatedText(bytes: ByteArray, start: Int, encoding: Int): Int {
        val end = findTextTerminator(bytes, start, encoding)
        if (end < 0) return bytes.size
        return end + if (encoding == 1 || encoding == 2) 2 else 1
    }

    private fun findTextTerminator(bytes: ByteArray, start: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var index = start
            while (index + 1 < bytes.size) {
                if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) return index
                index += 2
            }
            return -1
        }
        return bytes.indexOf(0, start)
    }

    private fun removeUnsynchronization(bytes: ByteArray): ByteArray {
        val output = ByteArray(bytes.size)
        var write = 0
        var read = 0
        while (read < bytes.size) {
            output[write++] = bytes[read]
            if (bytes[read] == 0xFF.toByte() && read + 1 < bytes.size && bytes[read + 1] == 0.toByte()) {
                read++
            }
            read++
        }
        return output.copyOf(write)
    }

    private fun readHeadAndTail(channel: FileChannel, windowSize: Int): List<ByteArray> {
        val size = channel.size()
        val headSize = min(size, windowSize.toLong()).toInt()
        val head = readRange(channel, 0L, headSize)
        if (size <= windowSize) return listOfNotNull(head)
        val tail = readRange(channel, size - windowSize, windowSize)
        return listOfNotNull(head, tail)
    }

    private fun readTail(channel: FileChannel, maxBytes: Int): ByteArray? {
        val length = min(channel.size(), maxBytes.toLong()).toInt()
        return readRange(channel, channel.size() - length, length)
    }

    private fun readRange(channel: FileChannel, offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length < 0 || offset + length > channel.size()) return null
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, position)
            if (count < 0) break
            if (count == 0) return null
            position += count
        }
        return buffer.array().takeIf { !buffer.hasRemaining() }
    }

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7f) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7f) shl 7) or
            (bytes[offset + 3].toInt() and 0x7f)

    private fun bigEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun unsigned24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 16) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            (bytes[offset + 2].toInt() and 0xff)

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.ISO_8859_1)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.indexOf(value: Int, start: Int): Int {
        for (index in start until size) if ((this[index].toInt() and 0xff) == value) return index
        return -1
    }

    private fun ByteArray.lastIndexOf(value: ByteArray): Int {
        for (index in size - value.size downTo 0) {
            if (value.indices.all { this[index + it] == value[it] }) return index
        }
        return -1
    }

    private fun ByteArray.indexOf(value: ByteArray, start: Int, endExclusive: Int): Int {
        val last = (endExclusive - value.size).coerceAtMost(size - value.size)
        for (index in start..last) {
            if (value.indices.all { this[index + it] == value[it] }) return index
        }
        return -1
    }

    private val ID3_MAGIC = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())
    private val FLAC_MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
    private val OGG_MAGIC = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
    private val OPUS_TAGS = "OpusTags".toByteArray(StandardCharsets.US_ASCII)
    private val VORBIS_COMMENT_MAGIC = byteArrayOf(3) + "vorbis".toByteArray(StandardCharsets.US_ASCII)
    private val APE_MAGIC = "APETAGEX".toByteArray(StandardCharsets.US_ASCII)
    private val LYRICS3_END = "LYRICS200".toByteArray(StandardCharsets.US_ASCII)
    private val LYRIC_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "LYRIC")

    private const val MAX_HEAD_BYTES = 4 * 1024 * 1024
    private const val MAX_TAIL_BYTES = 4 * 1024 * 1024
    private const val MAX_MP4_SCAN_BYTES = 8 * 1024 * 1024
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
    private const val MAX_APE_ITEMS = 4_096
    private const val MAX_VORBIS_COMMENTS = 16_384
}
