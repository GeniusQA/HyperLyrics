/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.provider

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import java.nio.charset.StandardCharsets
import java.util.Base64

interface OfficialProviderPlugin {
    fun install(host: OfficialProviderHost)
}

interface OfficialProviderHost {
    val packageName: String
    val processName: String

    fun hookApplication(callback: OfficialProviderApplicationCallback)

    fun hookMediaSession(
        playbackStateCallback: OfficialProviderPlaybackStateCallback,
        metadataCallback: OfficialProviderMetadataCallback,
    )

    fun resolveDexMethods(
        application: Application,
        queries: List<OfficialProviderDexMethodQuery>,
        callback: OfficialProviderDexMethodsCallback,
    )

    fun reportDexMethodValidation(
        cacheKey: String,
        valid: Boolean,
        detail: String? = null,
    ) = Unit

    /** Optional capability: false unless the core explicitly enables debug diagnostics.
     * New packs must fail closed when an older core has no such method.
     */
    fun isDiagnosticEnabled(): Boolean = false

    fun reportDiagnostic(
        tag: String,
        message: String,
    ) = Unit
}

fun interface OfficialProviderApplicationCallback {
    fun onApplicationCreated(application: Application)
}

fun interface OfficialProviderPlaybackStateCallback {
    fun onPlaybackStateChanged(state: PlaybackState?)
}

fun interface OfficialProviderMetadataCallback {
    fun onMetadataChanged(metadata: MediaMetadata?)
}

data class OfficialProviderMethodTarget(
    val className: String,
    val methodName: String,
    val parameterTypeNames: List<String> = emptyList(),
    val returnTypeName: String,
    val isStatic: Boolean,
)

enum class OfficialProviderDexTypeSource {
    DECLARING_CLASS,
    RETURN_TYPE,
    PARAMETER_TYPE,
}

data class OfficialProviderDexTypeReference(
    val queryCacheKey: String,
    val source: OfficialProviderDexTypeSource,
    val parameterIndex: Int = -1,
)

data class OfficialProviderDexMethodQuery(
    val cacheKey: String,
    val preferredTarget: OfficialProviderMethodTarget? = null,
    val declaringClassName: String? = null,
    val declaringClassNamePrefix: String? = null,
    val declaringClassReference: OfficialProviderDexTypeReference? = null,
    val requiredStrings: List<String> = emptyList(),
    val requiredInvokedMethodDescriptors: List<String> = emptyList(),
    val requiredInvokedMethodNames: List<String> = emptyList(),
    val parameterTypeNames: List<String>? = null,
    val parameterTypeReferences: Map<Int, OfficialProviderDexTypeReference> = emptyMap(),
    val returnTypeName: String? = null,
    val returnTypeNamePrefix: String? = null,
    val returnTypeReference: OfficialProviderDexTypeReference? = null,
    val returnTypeMatchesDeclaringClass: Boolean = false,
    val isStatic: Boolean? = null,
    val requiredCallerMethodNames: List<String> = emptyList(),
) {
    @Suppress("unused")
    @Deprecated("Binary compatibility for Provider Packs", level = DeprecationLevel.HIDDEN)
    constructor(
        cacheKey: String,
        preferredTarget: OfficialProviderMethodTarget? = null,
        declaringClassName: String? = null,
        declaringClassNamePrefix: String? = null,
        declaringClassReference: OfficialProviderDexTypeReference? = null,
        requiredStrings: List<String> = emptyList(),
        requiredInvokedMethodDescriptors: List<String> = emptyList(),
        requiredInvokedMethodNames: List<String> = emptyList(),
        parameterTypeNames: List<String>? = null,
        parameterTypeReferences: Map<Int, OfficialProviderDexTypeReference> = emptyMap(),
        returnTypeName: String? = null,
        returnTypeNamePrefix: String? = null,
        returnTypeReference: OfficialProviderDexTypeReference? = null,
        returnTypeMatchesDeclaringClass: Boolean = false,
        isStatic: Boolean? = null,
    ) : this(
        cacheKey = cacheKey,
        preferredTarget = preferredTarget,
        declaringClassName = declaringClassName,
        declaringClassNamePrefix = declaringClassNamePrefix,
        declaringClassReference = declaringClassReference,
        requiredStrings = requiredStrings,
        requiredInvokedMethodDescriptors = requiredInvokedMethodDescriptors,
        requiredInvokedMethodNames = requiredInvokedMethodNames,
        parameterTypeNames = parameterTypeNames,
        parameterTypeReferences = parameterTypeReferences,
        returnTypeName = returnTypeName,
        returnTypeNamePrefix = returnTypeNamePrefix,
        returnTypeReference = returnTypeReference,
        returnTypeMatchesDeclaringClass = returnTypeMatchesDeclaringClass,
        isStatic = isStatic,
        requiredCallerMethodNames = emptyList(),
    )

    @Suppress("unused")
    @Deprecated("Binary compatibility for Provider Packs", level = DeprecationLevel.HIDDEN)
    constructor(
        cacheKey: String,
        preferredTarget: OfficialProviderMethodTarget? = null,
        declaringClassName: String? = null,
        declaringClassNamePrefix: String? = null,
        requiredStrings: List<String> = emptyList(),
        requiredInvokedMethodDescriptors: List<String> = emptyList(),
        parameterTypeNames: List<String>? = null,
        returnTypeName: String? = null,
        returnTypeMatchesDeclaringClass: Boolean = false,
        isStatic: Boolean? = null,
    ) : this(
        cacheKey = cacheKey,
        preferredTarget = preferredTarget,
        declaringClassName = declaringClassName,
        declaringClassNamePrefix = declaringClassNamePrefix,
        declaringClassReference = null,
        requiredStrings = requiredStrings,
        requiredInvokedMethodDescriptors = requiredInvokedMethodDescriptors,
        requiredInvokedMethodNames = emptyList(),
        parameterTypeNames = parameterTypeNames,
        parameterTypeReferences = emptyMap(),
        returnTypeName = returnTypeName,
        returnTypeNamePrefix = null,
        returnTypeReference = null,
        returnTypeMatchesDeclaringClass = returnTypeMatchesDeclaringClass,
        isStatic = isStatic,
        requiredCallerMethodNames = emptyList(),
    )
}

fun interface OfficialProviderDexMethodsCallback {
    fun onMethodsResolved(targets: List<OfficialProviderMethodTarget>)
}

data class OfficialProviderNextTrackFrame(
    val clear: Boolean,
    val currentId: String,
    val currentTitle: String,
    val currentArtist: String,
    val nextId: String,
    val nextTitle: String,
    val nextArtist: String,
    val nextAlbum: String,
    val nextDurationMs: Long,
)

object OfficialProviderControlProtocol {
    const val NEXT_TRACK_PREFIX = "\u001eHLE_OFFICIAL_NEXT_TRACK_V1|"

    private const val UPDATE_OPERATION = "U"
    private const val CLEAR_OPERATION = "C"
    private const val FIELD_COUNT = 9
    private const val MAX_FRAME_LENGTH = 16 * 1024
    private const val MAX_FIELD_LENGTH = 1024

    fun encodeNextTrack(
        currentId: String,
        currentTitle: String,
        currentArtist: String,
        nextId: String,
        nextTitle: String,
        nextArtist: String,
        nextAlbum: String = "",
        nextDurationMs: Long = -1L,
    ): String = encode(
        UPDATE_OPERATION,
        currentId,
        currentTitle,
        currentArtist,
        nextId,
        nextTitle,
        nextArtist,
        nextAlbum,
        nextDurationMs,
    )

    fun encodeNextTrackClear(
        currentId: String = "",
        currentTitle: String = "",
        currentArtist: String = "",
    ): String = encode(
        CLEAR_OPERATION,
        currentId,
        currentTitle,
        currentArtist,
        "",
        "",
        "",
        "",
        -1L,
    )

    fun isReservedFrame(text: String?): Boolean = text?.startsWith(NEXT_TRACK_PREFIX) == true

    fun decodeNextTrack(text: String?): OfficialProviderNextTrackFrame? {
        if (!isReservedFrame(text) || text == null || text.length > MAX_FRAME_LENGTH) return null
        val fields = text.removePrefix(NEXT_TRACK_PREFIX).split('|')
        if (fields.size != FIELD_COUNT) return null
        val operation = fields[0]
        if (operation != UPDATE_OPERATION && operation != CLEAR_OPERATION) return null
        val decoded = fields.drop(1).dropLast(1).map { decodeField(it) ?: return null }
        val duration = fields.last().toLongOrNull()?.takeIf { it >= -1L } ?: return null
        val frame = OfficialProviderNextTrackFrame(
            clear = operation == CLEAR_OPERATION,
            currentId = decoded[0],
            currentTitle = decoded[1],
            currentArtist = decoded[2],
            nextId = decoded[3],
            nextTitle = decoded[4],
            nextArtist = decoded[5],
            nextAlbum = decoded[6],
            nextDurationMs = duration,
        )
        return frame.takeIf { it.clear || it.nextTitle.isNotBlank() }
    }

    private fun encode(
        operation: String,
        currentId: String,
        currentTitle: String,
        currentArtist: String,
        nextId: String,
        nextTitle: String,
        nextArtist: String,
        nextAlbum: String,
        nextDurationMs: Long,
    ): String = buildString {
        append(NEXT_TRACK_PREFIX)
        append(operation)
        listOf(
            currentId,
            currentTitle,
            currentArtist,
            nextId,
            nextTitle,
            nextArtist,
            nextAlbum,
        ).forEach { value ->
            append('|')
            append(encodeField(value.take(MAX_FIELD_LENGTH)))
        }
        append('|')
        append(nextDurationMs.coerceAtLeast(-1L))
    }

    private fun encodeField(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.length <= MAX_FIELD_LENGTH }
}
