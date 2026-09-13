/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

internal data class NeteaseYrcWordSegment(
    val begin: Long,
    val duration: Long,
    val text: String,
)

/**
 * Splits a YRC line body at timing markers while keeping the original text
 * between adjacent markers byte-for-byte, including Latin word separators.
 */
internal object NeteaseYrcWordParser {
    private val marker = Regex("\\((\\d+),(\\d+),\\d+\\)")

    fun parse(content: String): List<NeteaseYrcWordSegment> {
        val markers = marker.findAll(content).toList()
        return markers.mapIndexedNotNull { index, match ->
            val begin = match.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: 0L
            val textStart = match.range.last + 1
            val textEnd = markers.getOrNull(index + 1)?.range?.first ?: content.length
            val text = content.substring(textStart, textEnd)
            if (text.isEmpty()) return@mapIndexedNotNull null
            NeteaseYrcWordSegment(
                begin = begin,
                duration = duration,
                text = text,
            )
        }
    }
}
