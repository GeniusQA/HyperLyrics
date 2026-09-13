/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteasePlaybackForwardingPolicyTest {
    @Test fun `accepted playing paused stopped and seek anchors never enter manual fallback`() {
        assertEquals(NeteasePlaybackForwardingMode.AUTOMATIC,
            neteasePlaybackForwardingMode(true, false, true))
    }

    @Test fun `accepted buffering anchor remains authoritative`() {
        assertEquals(NeteasePlaybackForwardingMode.AUTOMATIC,
            neteasePlaybackForwardingMode(true, true, true))
    }

    @Test fun `failed or unavailable automatic path retains manual fallback`() {
        for (result in listOf(false, null)) {
            assertEquals(NeteasePlaybackForwardingMode.MANUAL_FALLBACK,
                neteasePlaybackForwardingMode(true, false, result))
        }
    }

    @Test fun `failed buffering does not restart a playing manual writer`() {
        for (result in listOf(false, null)) {
            assertEquals(NeteasePlaybackForwardingMode.PRESERVE_BUFFERING,
                neteasePlaybackForwardingMode(true, true, result))
        }
    }

    @Test fun `null state still stops the legacy player even if clearing auto state succeeds`() {
        for (result in listOf(true, false, null)) {
            assertEquals(NeteasePlaybackForwardingMode.MANUAL_FALLBACK,
                neteasePlaybackForwardingMode(false, false, result))
        }
    }

    @Test fun `fallback followed by late successful anchor switches back to automatic`() {
        assertEquals(NeteasePlaybackForwardingMode.MANUAL_FALLBACK,
            neteasePlaybackForwardingMode(true, false, false))
        repeat(3) {
            assertEquals(NeteasePlaybackForwardingMode.AUTOMATIC,
                neteasePlaybackForwardingMode(true, false, true))
        }
        assertEquals(NeteasePlaybackForwardingMode.MANUAL_FALLBACK,
            neteasePlaybackForwardingMode(true, false, false))
    }
}
