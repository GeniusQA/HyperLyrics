/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.app.Application
import com.genius.hyperlyrics.provider.OfficialProviderDexMethodQuery
import com.genius.hyperlyrics.provider.OfficialProviderMethodTarget

internal object QQMusicBufferHookResolver {
    const val BUFFER_START_CACHE_KEY = "qqmusic-mobile-buffer-start-v1"
    const val BUFFER_END_CACHE_KEY = "qqmusic-mobile-buffer-end-v1"

    private const val VERIFIED_CALLBACK_CLASS = "com.tencent.qqmusic.mediaplayer.i\$c"
    private const val READ_WAIT_END_STATUS =
        "com.tencent.qqmusic.mediaplayer.upstream.ReadWaitEndStatus"
    private const val VERIFIED_VERSION_NAME = "20.6.5.8"
    private const val VERIFIED_VERSION_CODE = 7_228L

    /**
     * The preferred descriptors below were verified from the original DEX in QQ Music
     * 20.6.5.8 (7228). The semantic strings are intentionally authoritative for later versions;
     * an obfuscation change must fall through to DexKit instead of extending hardcoded aliases.
     */
    fun queries(application: Application): List<OfficialProviderDexMethodQuery>? {
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        return queries(
            packageName = application.packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = packageInfo.longVersionCode,
        )
    }

    internal fun queries(
        packageName: String,
        versionName: String,
        versionCode: Long,
    ): List<OfficialProviderDexMethodQuery>? {
        if (packageName != QQMusicRuntimePlan.MOBILE_PACKAGE) return null
        val useVerifiedTargets =
            versionName == VERIFIED_VERSION_NAME && versionCode == VERIFIED_VERSION_CODE
        fun verifiedTarget(
            methodName: String,
            parameterTypeNames: List<String>,
        ) = OfficialProviderMethodTarget(
            className = VERIFIED_CALLBACK_CLASS,
            methodName = methodName,
            parameterTypeNames = parameterTypeNames,
            returnTypeName = "void",
            isStatic = false,
        ).takeIf { useVerifiedTargets }
        return listOf(
            OfficialProviderDexMethodQuery(
                cacheKey = BUFFER_START_CACHE_KEY,
                preferredTarget = verifiedTarget(
                    methodName = "d",
                    parameterTypeNames = listOf("long"),
                ),
                declaringClassNamePrefix = "com.tencent.qqmusic.mediaplayer.",
                requiredStrings = listOf("buffer started."),
                parameterTypeNames = listOf("long"),
                returnTypeName = "void",
                isStatic = false,
            ),
            OfficialProviderDexMethodQuery(
                cacheKey = BUFFER_END_CACHE_KEY,
                preferredTarget = verifiedTarget(
                    methodName = "f",
                    parameterTypeNames = listOf("long", "int", READ_WAIT_END_STATUS),
                ),
                declaringClassNamePrefix = "com.tencent.qqmusic.mediaplayer.",
                requiredStrings = listOf("buffer ended."),
                returnTypeName = "void",
                isStatic = false,
            ),
        )
    }
}
