/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import com.genius.hyperlyrics.provider.OfficialProviderDexTypeReference
import com.genius.hyperlyrics.provider.OfficialProviderDexTypeSource
import com.genius.hyperlyrics.provider.OfficialProviderMethodAnnotationConstraint
import com.genius.hyperlyrics.provider.OfficialProviderMethodTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyHookProfilesTest {
    @Test
    fun `selects the verified profile matching the host version code`() {
        assertEquals(144716725L, SpotifyHookProfiles.profileFor(144716725L).versionCode)
        assertEquals(145767611L, SpotifyHookProfiles.profileFor(145767611L).versionCode)
        assertEquals(
            144716725L,
            SpotifyHookProfiles.exactProfileFor(144716725L)?.versionCode,
        )
    }

    @Test
    fun `unknown host versions expose no exact profile and fall back explicitly`() {
        assertNull(SpotifyHookProfiles.exactProfileFor(144192416L))
        assertNull(SpotifyHookProfiles.exactProfileFor(999999999L))
        assertEquals(145767611L, SpotifyHookProfiles.fallbackProfile().versionCode)
        assertEquals(145767611L, SpotifyHookProfiles.profileFor(999999999L).versionCode)
    }

    @Test
    fun `chains annotation anchor queries before the client queries that reference them`() {
        val queries = SpotifyHookProfiles.lyricsChainQueries
        assertEquals(4, queries.size)
        val keys = queries.map { it.cacheKey }
        assertEquals(keys.size, keys.distinct().size)
        val clientIndexes = listOf(
            keys.indexOf(SpotifyHookProfiles.CHAIN_CLIENT_V2_KEY),
            keys.indexOf(SpotifyHookProfiles.CHAIN_CLIENT_V3_KEY),
        )
        val serviceIndexes = listOf(
            keys.indexOf(SpotifyHookProfiles.CHAIN_SERVICE_V2_KEY),
            keys.indexOf(SpotifyHookProfiles.CHAIN_SERVICE_V3_KEY),
        )
        clientIndexes.zip(serviceIndexes).forEach { (client, service) ->
            assertTrue("包装类查询必须晚于其服务查询", client > service)
        }
    }

    @Test
    fun `service queries anchor only the cross-version endpoint annotation values`() {
        val queries = SpotifyHookProfiles.lyricsChainQueries
        val keys = queries.map { it.cacheKey }
        val serviceV2 = queries[keys.indexOf(SpotifyHookProfiles.CHAIN_SERVICE_V2_KEY)]
        val serviceV3 = queries[keys.indexOf(SpotifyHookProfiles.CHAIN_SERVICE_V3_KEY)]

        // R8 每版都会改名 Retrofit 注解类（9.1.72=p.thy，9.1.80=p.vsz），
        // 锚点必须只锚注解元素值，不得携带注解类型名或元素名。
        assertEquals(
            OfficialProviderMethodAnnotationConstraint(
                elementValue = "color-lyrics/v2/track/{trackId}",
            ),
            serviceV2.requiredMethodAnnotation,
        )
        assertEquals(
            OfficialProviderMethodAnnotationConstraint(
                elementValue = "color-lyrics/v3/track/{trackId}",
            ),
            serviceV3.requiredMethodAnnotation,
        )
        assertNull(serviceV2.parameterTypeNames)
        assertNull(serviceV3.parameterTypeNames)
    }

    @Test
    fun `client queries hold the service declaring class and the b descriptor`() {
        val queries = SpotifyHookProfiles.lyricsChainQueries
        val keys = queries.map { it.cacheKey }
        mapOf(
            SpotifyHookProfiles.CHAIN_CLIENT_V2_KEY to SpotifyHookProfiles.CHAIN_SERVICE_V2_KEY,
            SpotifyHookProfiles.CHAIN_CLIENT_V3_KEY to SpotifyHookProfiles.CHAIN_SERVICE_V3_KEY,
        ).forEach { (clientKey, serviceKey) ->
            val query = queries[keys.indexOf(clientKey)]
            assertEquals(
                listOf(
                    OfficialProviderDexTypeReference(
                        queryCacheKey = serviceKey,
                        source = OfficialProviderDexTypeSource.DECLARING_CLASS,
                    ),
                ),
                query.declaringClassFieldReferences,
            )
            assertEquals(
                listOf("java.lang.String", "java.lang.String"),
                query.parameterTypeNames,
            )
            assertEquals("io.reactivex.rxjava3.core.Single", query.returnTypeName)
            assertEquals(false, query.isStatic)
        }
    }

    @Test
    fun `maps chain resolution targets into a runtime profile`() {
        val targets = listOf(
            OfficialProviderMethodTarget(
                className = "p.g980",
                methodName = "a",
                parameterTypeNames = listOf("java.lang.String", "boolean", "java.lang.String", "boolean"),
                returnTypeName = "io.reactivex.rxjava3.core.Single",
                isStatic = false,
            ),
            OfficialProviderMethodTarget(
                className = "p.xl80",
                methodName = "b",
                parameterTypeNames = listOf("java.lang.String", "boolean", "java.lang.String", "boolean"),
                returnTypeName = "io.reactivex.rxjava3.core.Single",
                isStatic = false,
            ),
            OfficialProviderMethodTarget(
                className = "p.lg80",
                methodName = "b",
                parameterTypeNames = listOf("java.lang.String", "java.lang.String"),
                returnTypeName = "io.reactivex.rxjava3.core.Single",
                isStatic = false,
            ),
            OfficialProviderMethodTarget(
                className = "p.am80",
                methodName = "b",
                parameterTypeNames = listOf("java.lang.String", "java.lang.String"),
                returnTypeName = "io.reactivex.rxjava3.core.Single",
                isStatic = false,
            ),
        )
        val profile = requireNotNull(
            SpotifyHookProfiles.chainProfile(versionCode = 144192416L, targets = targets),
        )

        assertEquals(144192416L, profile.versionCode)
        assertNull(profile.lyricsEndpointSelection)
        assertEquals(
            listOf(
                SpotifyLyricsEndpoint.V3 to "p.am80",
                SpotifyLyricsEndpoint.V2 to "p.lg80",
            ),
            profile.lyricsRequests.map { it.endpoint to it.target.className },
        )
        val v3Constructor = profile.lyricsClientConstructors
            .single { it.endpoint == SpotifyLyricsEndpoint.V3 }.target
        assertEquals("p.am80", v3Constructor.className)
        assertEquals("p.xl80", v3Constructor.firstParameterTypeName)
        assertEquals(emptyList<String>(), v3Constructor.parameterTypeNames)
        val v2Constructor = profile.lyricsClientConstructors
            .single { it.endpoint == SpotifyLyricsEndpoint.V2 }.target
        assertEquals("p.lg80", v2Constructor.className)
        assertEquals("p.g980", v2Constructor.firstParameterTypeName)
        assertEquals(emptyList<String>(), v2Constructor.parameterTypeNames)
    }

    @Test
    fun `rejects chain resolution targets with unexpected size`() {
        assertNull(
            SpotifyHookProfiles.chainProfile(
                versionCode = 1L,
                targets = emptyList(),
            ),
        )
        assertNull(
            SpotifyHookProfiles.chainProfile(
                versionCode = 1L,
                targets = List(SpotifyHookProfiles.lyricsChainQueries.size + 1) {
                    OfficialProviderMethodTarget(
                        className = "p.x",
                        methodName = "b",
                        returnTypeName = "io.reactivex.rxjava3.core.Single",
                        isStatic = false,
                    )
                },
            ),
        )
    }

    @Test
    fun `keeps exact 9172 lyrics targets from original dex`() {
        val profile = SpotifyHookProfiles.profileFor(144716725L)

        assertEquals(
            listOf(
                SpotifyLyricsEndpoint.V3 to "p.am80",
                SpotifyLyricsEndpoint.V2 to "p.lg80",
            ),
            profile.lyricsClientConstructors.map { it.endpoint to it.target.className },
        )
        assertEquals(
            listOf("p.xl80", "p.q2m", "p.xhe"),
            profile.lyricsClientConstructors
                .single { it.endpoint == SpotifyLyricsEndpoint.V3 }
                .target.parameterTypeNames,
        )
        assertEquals(
            listOf("p.g980", "p.q2m", "p.q2m", "p.xhe"),
            profile.lyricsClientConstructors
                .single { it.endpoint == SpotifyLyricsEndpoint.V2 }
                .target.parameterTypeNames,
        )

        val selection = requireNotNull(profile.lyricsEndpointSelection)
        assertEquals("p.hx3", selection.className)
        assertEquals("b", selection.methodName)
        assertEquals(emptyList<String>(), selection.parameterTypeNames)
        assertEquals("boolean", selection.returnTypeName)
        assertEquals(false, selection.isStatic)

        assertEquals(
            listOf(
                SpotifyLyricsEndpoint.V3 to "p.am80",
                SpotifyLyricsEndpoint.V2 to "p.lg80",
            ),
            profile.lyricsRequests.map { it.endpoint to it.target.className },
        )
        assertTrue(profile.lyricsRequests.none { it.target.className == "p.cla0" })
        assertTrue(profile.lyricsRequests.none { it.target.className == "p.kf80" })
    }

    @Test
    fun `keeps exact 9180 lyrics targets from original dex`() {
        val profile = SpotifyHookProfiles.profileFor(145767611L)

        assertEquals(
            listOf(
                SpotifyLyricsEndpoint.V3 to "p.vja0",
                SpotifyLyricsEndpoint.V2 to "p.gea0",
            ),
            profile.lyricsClientConstructors.map { it.endpoint to it.target.className },
        )
        assertEquals(
            listOf("p.sja0", "p.p4n", "p.qbf"),
            profile.lyricsClientConstructors
                .single { it.endpoint == SpotifyLyricsEndpoint.V3 }
                .target.parameterTypeNames,
        )
        assertEquals(
            listOf("p.h7a0", "p.p4n", "p.p4n", "p.qbf"),
            profile.lyricsClientConstructors
                .single { it.endpoint == SpotifyLyricsEndpoint.V2 }
                .target.parameterTypeNames,
        )

        assertNull(profile.lyricsEndpointSelection)

        assertEquals(
            listOf(
                SpotifyLyricsEndpoint.V3 to "p.vja0",
                SpotifyLyricsEndpoint.V2 to "p.gea0",
            ),
            profile.lyricsRequests.map { it.endpoint to it.target.className },
        )
    }

    @Test
    fun `every profile request keeps the cross-version b descriptor`() {
        listOf(144716725L, 145767611L).forEach { versionCode ->
            SpotifyHookProfiles.profileFor(versionCode).lyricsRequests.forEach { request ->
                assertEquals("b", request.target.methodName)
                assertEquals(
                    listOf("java.lang.String", "java.lang.String"),
                    request.target.parameterTypeNames,
                )
                assertEquals("io.reactivex.rxjava3.core.Single", request.target.returnTypeName)
                assertEquals(false, request.target.isStatic)
                assertNotEquals("p.v581", request.target.className)
            }
        }
    }

    @Test
    fun `maps enable v3 flag to the same endpoint as Spotify dependency injection`() {
        assertEquals(SpotifyLyricsEndpoint.V3, SpotifyLyricsEndpoint.fromEnableV3(true))
        assertEquals(SpotifyLyricsEndpoint.V2, SpotifyLyricsEndpoint.fromEnableV3(false))
    }

    @Test
    fun `keeps exact PlayerState nextTracks target and DexKit semantics`() {
        val query = SpotifyHookProfiles.queueStateQuery
        val target = requireNotNull(query.preferredTarget)

        assertEquals("p.zw21", target.className)
        assertEquals("g", target.methodName)
        assertEquals(
            listOf(SpotifyHookProfiles.PLAYER_STATE_CLASS, "boolean", "boolean"),
            target.parameterTypeNames,
        )
        assertTrue(query.requiredInvokedMethodNames.contains("nextTracks"))
        assertTrue(query.requiredInvokedMethodNames.contains("disallowSkippingNextReasons"))
    }

    @Test
    fun `keeps exact AutoValue PlayerState nextTracks accessor from original dex`() {
        val target = SpotifyHookProfiles.nextTracksAccessorTarget

        assertEquals("com.spotify.player.model.AutoValue_PlayerState", target.className)
        assertEquals("nextTracks", target.methodName)
        assertEquals(emptyList<String>(), target.parameterTypeNames)
        assertEquals("p.f320", target.returnTypeName)
        assertEquals(false, target.isStatic)
    }

    @Test
    fun `debounces transient invalid hook callbacks`() {
        val tracker = SpotifyPluginEntry.SpotifyHookValidationTracker(invalidThreshold = 3)

        assertEquals(false, tracker.record(valid = false))
        assertEquals(false, tracker.record(valid = false))
        assertEquals(true, tracker.record(valid = false))
        assertEquals(false, tracker.record(valid = true))
        assertEquals(false, tracker.record(valid = false))
    }
}
