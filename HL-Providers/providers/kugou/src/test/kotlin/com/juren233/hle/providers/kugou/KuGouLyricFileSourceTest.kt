/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import com.juren233.hle.providers.kugou.KuGouPluginEntry.KrcDecryptor
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouLyricFileSourceTest {

    @Test
    fun `lyric file query anchors LyricManager by its error string`() {
        val query = KuGouPluginEntry.lyricFileQuery()

        assertEquals("kugou-lyric-file-load-v1", query.cacheKey)
        assertEquals("com.kugou.framework.lyric.LyricManager", query.declaringClassName)
        assertEquals(listOf("file is not krc or lyc or txt file"), query.requiredStrings)
        assertEquals(listOf("java.lang.String", "boolean"), query.parameterTypeNames)
        assertEquals(false, query.isStatic)
        assertNull(query.preferredTarget)
    }

    @Test
    fun `track without media id and title can not be bound from a file hit`() {
        assertTrue(
            KuGouLyricFilePolicy.isBindableTrack(
                KuGouTrackMetadata(mediaId = "12345", title = null, artist = null, album = null, durationMs = 0L),
            ),
        )
        assertTrue(
            KuGouLyricFilePolicy.isBindableTrack(
                KuGouTrackMetadata(mediaId = null, title = "歌名", artist = null, album = null, durationMs = 0L),
            ),
        )
        assertFalse(
            KuGouLyricFilePolicy.isBindableTrack(
                KuGouTrackMetadata(mediaId = null, title = null, artist = null, album = null, durationMs = 0L),
            ),
        )
    }

    @Test
    fun `file binding is dropped once the player moved to another track`() {
        assertTrue(
            KuGouLyricFilePolicy.isStillCurrent(boundIdentity = "a", currentIdentity = "a"),
        )
        assertFalse(
            KuGouLyricFilePolicy.isStillCurrent(boundIdentity = "a", currentIdentity = "b"),
        )
    }

    @Test
    fun `file source owns the track and suppresses the search source`() {
        assertTrue(KuGouLyricFilePolicy.ownsTrack(fileSourceIdentity = "a", identity = "a"))
        assertFalse(KuGouLyricFilePolicy.ownsTrack(fileSourceIdentity = "a", identity = "b"))
        assertFalse(KuGouLyricFilePolicy.ownsTrack(fileSourceIdentity = null, identity = "a"))
    }

    @Test
    fun `krc decrypt accepts payload with and without the krc1 magic`() {
        val content = "[1000,2000]第一句\n[3000,2000]第二句"
        val deflated = deflate(content.toByteArray(Charsets.UTF_8))

        val withMagic = "krc1".toByteArray() + xorKey(deflated)
        val withoutMagic = xorKey(deflated)

        assertEquals(content, KrcDecryptor.decrypt(withMagic))
        assertEquals(content, KrcDecryptor.decrypt(withoutMagic))
        assertNull(KrcDecryptor.decrypt("this is not krc".toByteArray()))
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun xorKey(input: ByteArray): ByteArray {
        val key = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
            206.toByte(), 210.toByte(), 110, 105,
        )
        return ByteArray(input.size) { index ->
            (input[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
    }
}
