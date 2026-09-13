/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import org.junit.Assert.*
import org.junit.Test

class NeteasePlaybackDiagnosticSamplerTest {
    @Test fun `callback correlation increases without influencing playback selection`() {
        val sampler = NeteasePlaybackDiagnosticSampler()
        assertEquals(1L, sampler.nextSequence())
        assertEquals(2L, sampler.nextSequence())
        assertEquals(2L, sampler.currentSequence)
    }

    @Test fun `first write and failures are immediate while stable ticks are bounded`() {
        val sampler = NeteasePlaybackDiagnosticSampler()
        assertEquals("first_write", sampler.sampleWrite(0, 1, true))
        assertNull(sampler.sampleWrite(41, 1, true))
        assertEquals("result_changed", sampler.sampleWrite(82, 1, false))
        assertEquals("result_changed", sampler.sampleWrite(123, 1, true))
        assertNull(sampler.sampleWrite(5_122, 1, true))
        assertEquals("periodic", sampler.sampleWrite(5_123, 1, true))
    }

    @Test fun `new callback produces a fresh writer sample without waiting five seconds`() {
        val sampler = NeteasePlaybackDiagnosticSampler()
        sampler.sampleWrite(0, 1, true)
        assertEquals("callback_changed", sampler.sampleWrite(1, 2, true))
    }

    @Test fun `writer exit is deduplicated only within the same callback and reason`() {
        val sampler = NeteasePlaybackDiagnosticSampler()
        assertTrue(sampler.sampleExit(1, "manual_mode_disabled"))
        assertFalse(sampler.sampleExit(1, "manual_mode_disabled"))
        assertTrue(sampler.sampleExit(1, "no_state"))
        assertTrue(sampler.sampleExit(2, "manual_mode_disabled"))
    }
}
