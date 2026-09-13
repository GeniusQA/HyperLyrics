/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouApiProtocolTest {
    private val searchParameters = mapOf(
        "album_audio_id" to "0",
        "appid" to "1005",
        "clientver" to "20759",
        "duration" to "269000",
        "hash" to "",
        "keyword" to "周杰伦 - 晴天",
        "lrctxt" to "1",
        "man" to "yes",
        "query_copyright" to "1",
    )

    @Test
    fun `matches the signature verified against Kugou v2 search`() {
        assertEquals(
            "05bc38d0cc855ae66995137e7e62900a",
            KuGouApiProtocol.signature(searchParameters),
        )
    }

    @Test
    fun `sorts query parameters and appends signature`() {
        val query = KuGouApiProtocol.signedQuery(searchParameters)
        val keys = query.split('&').map { it.substringBefore('=') }

        assertEquals(keys.sorted(), keys)
        assertTrue(query.contains("keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6+-+%E6%99%B4%E5%A4%A9"))
        assertTrue(query.endsWith("signature=05bc38d0cc855ae66995137e7e62900a"))
    }
}
