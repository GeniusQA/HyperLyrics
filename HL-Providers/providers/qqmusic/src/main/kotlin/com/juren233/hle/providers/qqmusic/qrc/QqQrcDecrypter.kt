/*
 * Portions of the QQ QRC decryption algorithm are derived from LyricProvider
 * (Apache License 2.0, Copyright 2026 Proify, Tomakino).
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic.qrc

import java.util.zip.InflaterInputStream

/** Decodes QQ Music's hex encoded, custom 3DES and zlib-compressed QRC. */
internal object QqQrcDecrypter {
    private val qqKey = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)
    private val decryptSchedule = Array(3) { Array(16) { ByteArray(6) } }.also {
        DESHelper.tripleDESKeySetup(qqKey, it, DESHelper.DECRYPT)
    }

    fun decode(value: String?): String? {
        val input = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (!isHexString(input)) return input

        return runCatching {
            val encrypted = hexToBytes(input)
            val decrypted = ByteArray(encrypted.size)
            val inputBlock = ByteArray(8)
            val outputBlock = ByteArray(8)
            for (offset in encrypted.indices step 8) {
                inputBlock.fill(0)
                val blockSize = minOf(8, encrypted.size - offset)
                System.arraycopy(encrypted, offset, inputBlock, 0, blockSize)
                DESHelper.tripleDESCrypt(inputBlock, outputBlock, decryptSchedule)
                System.arraycopy(outputBlock, 0, decrypted, offset, blockSize)
            }
            InflaterInputStream(decrypted.inputStream()).use { it.readBytes() }
                .toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun isHexString(value: String): Boolean =
        value.length % 2 == 0 && value.isNotEmpty() && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
