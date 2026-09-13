/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import io.reactivex.rxjava3.core.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SpotifySingleSuccessObserverTest {
    @Test
    fun `keeps Single deferred and preserves its success value`() {
        val value = Any()
        val original = Single.just(value)
        val observed = mutableListOf<Pair<String, Any>>()

        val wrapped = SpotifySingleSuccessObserver.wrap(
            result = original,
            trackUri = TRACK_A,
            onSuccess = { trackUri, result -> observed += trackUri to result },
        )

        assertNotSame(original, wrapped)
        assertEquals(emptyList<Pair<String, Any>>(), observed)
        assertSame(value, (wrapped as Single<*>).blockingGet())
        assertEquals(listOf(TRACK_A to value), observed)
    }

    @Test
    fun `binds concurrent success values to their own request URI`() {
        val valueA = Any()
        val valueB = Any()
        val observed = mutableListOf<Pair<String, Any>>()
        val wrappedA = SpotifySingleSuccessObserver.wrap(
            Single.just(valueA),
            TRACK_A,
            { trackUri, result -> observed += trackUri to result },
        ) as Single<*>
        val wrappedB = SpotifySingleSuccessObserver.wrap(
            Single.just(valueB),
            TRACK_B,
            { trackUri, result -> observed += trackUri to result },
        ) as Single<*>

        wrappedB.blockingGet()
        wrappedA.blockingGet()

        assertEquals(listOf(TRACK_B to valueB, TRACK_A to valueA), observed)
    }

    @Test
    fun `observer failures cannot turn Spotify success into an error`() {
        val value = Any()
        val original = Single.just(value)
        val failures = mutableListOf<Throwable>()
        val wrapped = SpotifySingleSuccessObserver.wrap(
            result = original,
            trackUri = TRACK_A,
            onSuccess = { _, _ -> error("observer failure") },
            onObserverFailure = failures::add,
        ) as Single<*>

        assertSame(value, wrapped.blockingGet())
        assertEquals(1, failures.size)
        assertEquals("observer failure", failures.single().message)
    }

    @Test
    fun `invalid request identity leaves the original Single untouched`() {
        val original = Single.just(Any())

        val wrapped = SpotifySingleSuccessObserver.wrap(original, "not-a-track", { _, _ -> })

        assertSame(original, wrapped)
    }

    private companion object {
        const val TRACK_A = "spotify:track:AAAAAAAAAAAAAAAAAAAAAA"
        const val TRACK_B = "spotify:track:BBBBBBBBBBBBBBBBBBBBBB"
    }
}
