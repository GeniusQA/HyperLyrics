/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import org.json.JSONObject
import java.util.Base64

internal object KuGouLanguageParser {
    private const val TRANSLATION_TYPE = 1
    private val languageTag = Regex("^\\[language:(.*)]$", RegexOption.IGNORE_CASE)

    fun translations(content: String, expectedLineCount: Int): List<String?>? {
        if (expectedLineCount <= 0) return null
        val encoded = content.lineSequence()
            .map(String::trim)
            .mapNotNull { languageTag.matchEntire(it)?.groupValues?.getOrNull(1) }
            .firstOrNull(String::isNotBlank)
            ?: return null
        val decoded = runCatching {
            Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
        }.getOrNull() ?: return null
        val contentSections = runCatching {
            JSONObject(decoded).optJSONArray("content")
        }.getOrNull() ?: return null

        for (sectionIndex in 0 until contentSections.length()) {
            val section = contentSections.optJSONObject(sectionIndex) ?: continue
            if (section.optInt("type", -1) != TRANSLATION_TYPE) continue
            val lyricContent = section.optJSONArray("lyricContent") ?: continue
            if (lyricContent.length() != expectedLineCount) return null
            return List(expectedLineCount) { lineIndex ->
                val chunks = lyricContent.optJSONArray(lineIndex) ?: return@List null
                buildString {
                    for (chunkIndex in 0 until chunks.length()) {
                        append(chunks.optString(chunkIndex, ""))
                    }
                }.trim().takeIf(String::isNotBlank)
            }
        }
        return null
    }
}
