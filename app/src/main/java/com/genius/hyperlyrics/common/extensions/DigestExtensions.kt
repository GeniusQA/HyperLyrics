package com.genius.hyperlyrics.common.extensions

import java.security.MessageDigest

/**
 * 统一的摘要十六进制输出。
 *
 * 之前 ProviderPack、OfficialProviderRepository、OfficialProviderDexMethodCacheCodec、
 * AppleDebugNetworkHooks 各维护一份 MessageDigest("SHA-256")，现统一到此处。
 */
fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

fun String.sha256Hex(): String = toByteArray(Charsets.UTF_8).sha256Hex()
