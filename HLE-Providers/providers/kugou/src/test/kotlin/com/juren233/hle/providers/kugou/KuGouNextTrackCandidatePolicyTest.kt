/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouNextTrackCandidatePolicyTest {
    @Test
    fun `rejects the current song returned as the next candidate`() {
        assertTrue(
            KuGouNextTrackCandidatePolicy.isCurrent(
                currentTitle = "四人游",
                currentArtist = "方大同、薛凯琪",
                candidateTitle = "  四人游 ",
                candidateArtist = "方大同、薛凯琪",
            ),
        )
    }

    @Test
    fun `keeps a real next song`() {
        assertFalse(
            KuGouNextTrackCandidatePolicy.isCurrent(
                currentTitle = "四人游",
                currentArtist = "方大同、薛凯琪",
                candidateTitle = "爱情讯息",
                candidateArtist = "郭静",
            ),
        )
    }

    @Test
    fun `does not reject when current metadata is unavailable`() {
        assertFalse(
            KuGouNextTrackCandidatePolicy.isCurrent(
                currentTitle = null,
                currentArtist = null,
                candidateTitle = "爱情讯息",
                candidateArtist = "郭静",
            ),
        )
    }

    @Test
    fun `requires repeated current-song candidates before repair`() {
        val tracker = KuGouNextTrackValidationTracker(invalidThreshold = 3)

        assertFalse(tracker.record(candidateMatchesCurrent = true))
        assertFalse(tracker.record(candidateMatchesCurrent = true))
        assertTrue(tracker.record(candidateMatchesCurrent = true))
        assertFalse(tracker.record(candidateMatchesCurrent = false))
        assertFalse(tracker.record(candidateMatchesCurrent = true))
    }
}
