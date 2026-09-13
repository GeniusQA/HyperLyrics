/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuwoRequestGuardTest {
    @Test
    fun `late result from previous song is rejected after switch`() {
        val guard = KuwoRequestGuard()

        assertTrue(guard.select("rid:1"))
        assertTrue(guard.isCurrent("rid:1"))
        assertTrue(guard.select("rid:2"))
        assertFalse(guard.isCurrent("rid:1"))
        assertTrue(guard.isCurrent("rid:2"))
    }

    @Test
    fun `duplicate metadata does not start another request`() {
        val guard = KuwoRequestGuard()

        assertTrue(guard.select("rid:81466699"))
        assertFalse(guard.select("rid:81466699"))
        guard.clear()
        assertFalse(guard.isCurrent("rid:81466699"))
    }
}
