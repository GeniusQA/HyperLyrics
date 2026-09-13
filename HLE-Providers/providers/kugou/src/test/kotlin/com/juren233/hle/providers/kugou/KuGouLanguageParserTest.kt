/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KuGouLanguageParserTest {
    @Test
    fun `parses type one language section as line translations`() {
        val content = krcWithLanguage(
            """{"version":1,"content":[{"language":0,"type":1,"lyricContent":[["First ","line"],["第二行"]]},{"language":0,"type":0,"lyricContent":[["first"],["di er hang"]]}]}""",
        )

        assertEquals(
            listOf("First line", "第二行"),
            KuGouLanguageParser.translations(content, expectedLineCount = 2),
        )
    }

    @Test
    fun `rejects translation section whose line count does not match lyrics`() {
        val content = krcWithLanguage(
            """{"version":1,"content":[{"language":0,"type":1,"lyricContent":[["only one"]]}]}""",
        )

        assertNull(KuGouLanguageParser.translations(content, expectedLineCount = 2))
    }

    @Test
    fun `returns null when krc has romanization but no translation`() {
        val content = krcWithLanguage(
            """{"version":1,"content":[{"language":0,"type":0,"lyricContent":[["roma"]]}]}""",
        )

        assertNull(KuGouLanguageParser.translations(content, expectedLineCount = 1))
    }

    private fun krcWithLanguage(json: String): String {
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        return "[language:$encoded]\n[0,1000]<0,1000,0>test"
    }
}
