/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicSongMidResolverTest {
    @Test
    fun `treats numeric media ids as numeric song ids`() {
        assertTrue(QQMusicSongMidResolver.isNumericSongId("97773"))
        assertTrue(QQMusicSongMidResolver.isNumericSongId("0"))
        assertTrue(QQMusicSongMidResolver.isNumericSongId("509076686"))
    }

    @Test
    fun `treats songmid shaped ids as non numeric`() {
        assertFalse(QQMusicSongMidResolver.isNumericSongId("0039MnYb0qxYhV"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId("003Qui1q2u1Zho"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId(""))
        assertFalse(QQMusicSongMidResolver.isNumericSongId("97773abc"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId(" 97773"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId("-1"))
    }
}
